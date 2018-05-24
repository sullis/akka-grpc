/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.grpc.internal

import akka.annotation.InternalApi
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.stream.stage._
import io.grpc._
import io.grpc.stub.StreamObserver

import scala.concurrent.{ Future, Promise }

@InternalApi
private object NewAkkaGrpcGraphStage {
  sealed trait ControlMessage
  case object ReadyForSending extends ControlMessage
  case class Closed(status: Status, trailer: Metadata) extends ControlMessage
}

/**
 * Wrapper graph stage representing a gRPC Netty call as a Flow[I, O, ForLater].
 *
 * Interaction is done through two parts, the ClientCall object and a listener registered
 * to the same which gets callbacks from the client:
 *
 *                                  _________________
 *  Flow in       ------ I------>  |                | ------- O ------->  Flow out
 *                                 |  Netty client  |
 *  upstream pull <-- onReady ---  |      call      | <-- request(1) ---  downstream pull
 *                                 ------------------
 *
 *
 * @param streamingResponse Do we expect a stream of responses or does more than 1 response mean a faulty server?
 */
@InternalApi
private final class NewAkkaGrpcGraphStage[I, O](
  descriptor: MethodDescriptor[I, O],
  fqMethodName: String,
  channel: Channel,
  options: CallOptions,
  streamingResponse: Boolean) extends GraphStageWithMaterializedValue[FlowShape[I, O], Future[Any]] {

  val in = Inlet[I](fqMethodName + ".in")
  val out = Outlet[O](fqMethodName + ".out")

  override val shape: FlowShape[I, O] = FlowShape.of(in, out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Any]) = {
    import NewAkkaGrpcGraphStage._
    val matVal = Promise[AnyRef]()

    val logic = new GraphStageLogic(shape) with InHandler with OutHandler {

      // this is here just to fail single response requests getting more responses
      // duplicating behavior in io.grpc.stub.ClientCalls
      var sawFirstElement = false
      var requested = 0

      // any here to avoid wrapping every incoming element
      val callback = getAsyncCallback[Any] {
        case msg: ControlMessage =>
          msg match {
            case ReadyForSending => if (!isClosed(in) && !hasBeenPulled(in)) tryPull(in)
            case Closed(status, trailer) => onCallClosed(status, trailer)
          }
        case element: O @unchecked =>
          if (!streamingResponse) {
            if (sawFirstElement) {
              throw new IllegalStateException("Got more than one messages back from to a non-streaming call")
            } else sawFirstElement = true
          }
          emit(out, element)
          requested -= 1
      }

      var call: ClientCall[I, O] = null

      val listener = new ClientCall.Listener[O] {
        override def onReady(): Unit = {
          callback.invoke(ReadyForSending)
        }
        override def onHeaders(headers: Metadata): Unit = {
          matVal.success(headers) // FIXME a proper type here - also we need the trailers somehow
        }
        override def onMessage(message: O): Unit = {
          callback.invoke(message)
        }
        override def onClose(status: Status, trailers: Metadata): Unit = {
          callback.invoke(Closed(status, trailers))
        }
      }
      override def preStart(): Unit = {
        call = channel.newCall(descriptor, options)
        call.start(listener, new Metadata()) // actual request metadata is already in options above

        // always pull early - pull 2 for non-streaming response "to trigger failure early"
        // duplicating behavior in io.grpc.stub.ClientCalls - not sure why this is a good idea
        val initialRequest = if (streamingResponse) 1 else 2
        call.request(initialRequest)
        requested = initialRequest

        // give us a chance to deal with the call cancellation even when
        // the up and downstreams are done
        setKeepGoing(true)

        // the netty client doesn't always start with an OnReady, but all calls has at least one
        // request so pull early to get things going
        pull(in)
      }
      override def onPush(): Unit = {
        call.sendMessage(grab(in))
        if (call.isReady && !hasBeenPulled(in)) {
          pull(in)
        }
      }
      override def onUpstreamFinish(): Unit = {
        call.halfClose()
        if (isClosed(out)) {
          call.cancel("Upstream completed and downstream has cancelled", null)
          call = null
          completeStage()
        }
      }
      override def onUpstreamFailure(ex: Throwable): Unit = {
        call.cancel("Failure from upstream", ex)
        call = null
        failStage(ex)
      }

      override def onPull(): Unit = {
        if (requested == 0) {
          call.request(1)
          requested += 1
        }
      }
      override def onDownstreamFinish(): Unit = {
        if (isClosed(out)) {
          call.cancel("Downstream cancelled", null)
          call = null
          completeStage()
        }
      }

      def onCallClosed(status: Status, trailers: Metadata): Unit = {
        if (status.isOk()) {
          // FIXME share trailers through matval
          completeStage()
        } else {
          failStage(status.asRuntimeException(trailers))
        }
        call = null
      }

      override def postStop(): Unit = {
        if (call != null) {
          call.cancel("Abrupt stream termination", null)
          call = null
        }
      }

      setHandlers(in, out, this)
    }

    (logic, matVal.future)
  }

}