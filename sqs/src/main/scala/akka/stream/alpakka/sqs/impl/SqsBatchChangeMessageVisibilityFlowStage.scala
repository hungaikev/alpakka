/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.sqs.impl

import akka.annotation.InternalApi
import akka.stream.alpakka.sqs.{MessageAction, SqsAckResult, SqsBatchException}
import akka.stream.stage.{AsyncCallback, GraphStage, GraphStageLogic}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import com.amazonaws.handlers.AsyncHandler
import com.amazonaws.services.sqs.AmazonSQSAsync
import com.amazonaws.services.sqs.model.{
  ChangeMessageVisibilityBatchRequest,
  ChangeMessageVisibilityBatchRequestEntry,
  ChangeMessageVisibilityBatchResult
}

import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}

/**
 * INTERNAL API
 */
@InternalApi private[sqs] final class SqsBatchChangeMessageVisibilityFlowStage(queueUrl: String,
                                                                               sqsClient: AmazonSQSAsync)
    extends GraphStage[FlowShape[Iterable[MessageAction.ChangeMessageVisibility], Future[List[SqsAckResult]]]] {
  private val in = Inlet[Iterable[MessageAction.ChangeMessageVisibility]]("messages")
  private val out = Outlet[Future[List[SqsAckResult]]]("results")
  override val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new SqsBatchStageLogic(shape) {
      private def handleChangeVisibility(request: ChangeMessageVisibilityBatchRequest): Unit = {
        val entries = request.getEntries
        for (entry <- entries.asScala)
          log.debug(s"Set visibility timeout for message {} to {}", entry.getReceiptHandle, entry.getVisibilityTimeout)
        inFlight -= entries.size()
        checkForCompletion()
      }
      private var changeVisibilityCallback: AsyncCallback[ChangeMessageVisibilityBatchRequest] = _
      override def preStart(): Unit = {
        super.preStart()
        changeVisibilityCallback = getAsyncCallback[ChangeMessageVisibilityBatchRequest](handleChangeVisibility)
      }
      override def onPush() = {
        val messagesIt = grab(in)
        val messages = messagesIt.toList
        val nrOfMessages = messages.size
        val responsePromise = Promise[List[SqsAckResult]]
        inFlight += nrOfMessages

        sqsClient
          .changeMessageVisibilityBatchAsync(
            new ChangeMessageVisibilityBatchRequest(
              queueUrl,
              messages.zipWithIndex.map {
                case (action, index) =>
                  new ChangeMessageVisibilityBatchRequestEntry()
                    .withReceiptHandle(action.message.getReceiptHandle)
                    .withVisibilityTimeout(action.visibilityTimeout)
                    .withId(index.toString)
              }.asJava
            ),
            new AsyncHandler[ChangeMessageVisibilityBatchRequest, ChangeMessageVisibilityBatchResult]() {
              override def onError(exception: Exception): Unit = {
                val batchException = new SqsBatchException(messages.size, exception)
                responsePromise.failure(batchException)
                failureCallback.invoke(batchException)
              }

              override def onSuccess(request: ChangeMessageVisibilityBatchRequest,
                                     result: ChangeMessageVisibilityBatchResult): Unit =
                if (!result.getFailed.isEmpty) {
                  val nrOfFailedMessages = result.getFailed.size()
                  val batchException: SqsBatchException =
                    new SqsBatchException(
                      batchSize = nrOfMessages,
                      cause = new Exception(
                        s"Some messages failed to change visibility. $nrOfFailedMessages of $nrOfMessages messages failed"
                      )
                    )
                  responsePromise.failure(batchException)
                  failureCallback.invoke(batchException)
                } else {
                  responsePromise.success(messages.map(msg => SqsAckResult(Some(result), msg.message.getBody)))
                  changeVisibilityCallback.invoke(request)
                }
            }
          )
        push(out, responsePromise.future)
      }
    }
}
