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

import javax.inject.{Inject, Singleton}
import com.google.inject.ImplementedBy
import play.api.libs.json.{Format, Json, OFormat}
import reactivemongo.api.bson.BSONDocument
import reactivemongo.api.bson.collection.BSONSerializationPack
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID
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

  createIndexes(Seq(
      defineIndex(
        key = Seq("conversationId" -> IndexType.Ascending),
        name = Some("conversationId-Index"),
        unique = true
      ),
      defineIndex(
        key = Seq("createdDate" -> IndexType.Ascending),
        name = Some("createdDate-Index"),
        options = BSONDocument("expireAfterSeconds" -> metricsConfig.ttlInSeconds)
      )
  ))

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

  private def createIndexes(indexes: Seq[Index]): Future[Seq[Boolean]] =
    Future.sequence{ indexes.map{ index => collection.indexesManager.ensure(index) } }

  private def defineIndex(key: Seq[(String, IndexType)],
                          name: Option[String],
                          unique: Boolean = false,
                          background: Boolean = false,
                          options: BSONDocument = BSONDocument.empty) =
    Index(BSONSerializationPack)(key, name, unique, background, false, false, None, None, None, None, None, None, None, None, None, None, None, None, None, None, None, options)
}
