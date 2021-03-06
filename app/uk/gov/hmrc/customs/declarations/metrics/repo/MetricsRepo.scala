/*
 * Copyright 2020 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.customs.declarations.metrics.repo

import com.google.inject.ImplementedBy
import javax.inject.{Inject, Singleton}
import play.api.libs.json.{Format, Json, OFormat}
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.{BSONDocument, BSONObjectID}
import reactivemongo.play.json.JsObjectDocumentWriter
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.declarations.metrics.model.{ConversationMetric, ConversationMetrics, Event, MetricsConfig}
import uk.gov.hmrc.mongo.ReactiveRepository

import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[MetricsMongoRepo])
trait MetricsRepo {

  def save(conversationMetrics: ConversationMetrics): Future[Boolean]
  def updateWithFirstNotification(conversationMetric: ConversationMetric): Future[ConversationMetrics]
}

@Singleton
class MetricsMongoRepo @Inject() (mongoDbProvider: MongoDbProvider,
                                  errorHandler: MetricsRepoErrorHandler,
                                  logger: CdsLogger,
                                  metricsConfig: MetricsConfig)(implicit ec: ExecutionContext) extends ReactiveRepository[ConversationMetrics, BSONObjectID](
  collectionName = "metrics",
  mongo = mongoDbProvider.mongo,
  domainFormat = ConversationMetrics.conversationMetricsJF
) with MetricsRepo {

  private implicit val format: OFormat[ConversationMetrics] = ConversationMetrics.conversationMetricsJF
  private implicit val formatEvent: Format[Event] = Event.EventJF

  private val ttlIndexName = "createdDate-Index"
  private val ttlInSeconds = metricsConfig.ttlInSeconds
  private val ttlIndex = Index(
    key = Seq("createdAt" -> IndexType.Descending),
    name = Some(ttlIndexName),
    unique = false,
    options = BSONDocument("expireAfterSeconds" -> ttlInSeconds)
  )

  dropInvalidIndexes.flatMap { _ =>
    collection.indexesManager.ensure(ttlIndex)
  }

  override def indexes: Seq[Index] = Seq(
    Index(
      key = Seq("conversationId" -> IndexType.Ascending),
      name = Some("conversationId-Index"),
      unique = true
    ),
    ttlIndex
  )

  override def save(conversationMetrics: ConversationMetrics): Future[Boolean] = {
    logger.debug(s"saving conversationMetrics: $conversationMetrics")
    lazy val errorMsg = s"event data not inserted for $conversationMetrics"

    insert(conversationMetrics).map {
      writeResult => errorHandler.handleSaveError(writeResult, errorMsg)
    }
  }

  override def updateWithFirstNotification(conversationMetric: ConversationMetric): Future[ConversationMetrics] = {
    logger.debug(s"updating with first notification: $conversationMetric")
    lazy val errorMsg = s"event data not updated for $conversationMetric"

    val selector = Json.obj("conversationId" -> conversationMetric.conversationId.id, "events.1" -> Json.obj("$exists" -> false))
    val update = Json.obj("$push" -> Json.obj("events" -> conversationMetric.event))

    val result: Future[ConversationMetrics] = findAndUpdate(selector, update, fetchNewObject = true).map { result =>

      if (result.lastError.isDefined && result.lastError.get.err.isDefined) {
          logger.error(s"mongo error: ${result.lastError.get.err.get}")
          throw new IllegalStateException(errorMsg)
      } else {
        result.result[ConversationMetrics].getOrElse({
          logger.debug(errorMsg)
          throw new IllegalStateException(errorMsg)
        })
      }
    }
    result
  }

  private def dropInvalidIndexes: Future[_] =
    collection.indexesManager.list().flatMap { indexes =>
      indexes
        .find { index =>
          index.name.contains(ttlIndexName) &&
            !index.options.getAs[Int]("expireAfterSeconds").contains(ttlInSeconds)
        }
        .map { _ =>
          logger.debug(s"dropping $ttlIndexName index as ttl value is incorrect")
          collection.indexesManager.drop(ttlIndexName)
        }
        .getOrElse(Future.successful(()))
    }
}
