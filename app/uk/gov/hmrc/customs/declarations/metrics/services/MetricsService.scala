/*
 * Copyright 2018 HM Revenue & Customs
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

package uk.gov.hmrc.customs.declarations.metrics.services

import java.time.Duration
import javax.inject.Inject

import com.kenshoo.play.metrics.Metrics
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.declarations.metrics.model.{EventTime, EventTimeStamp, EventType}
import uk.gov.hmrc.customs.declarations.metrics.repo.MetricsRepo

import scala.concurrent.Future

class MetricsService @Inject()(logger: CdsLogger, metricsRepo: MetricsRepo, val metrics: Metrics) extends HasMetrics {

  def process(eventTime: EventTime): Future[Either[ErrorResponse, Boolean]] = {
    metricsRepo.save(eventTime)

    eventTime.eventType match {
      case EventType("DECLARATION") =>
        //dec_start => store graphite count & calc elapsed time from two timestamps & store in Mongo
        //TODO check boolean returned
        val success = metricsRepo.save(eventTime)
        recordTime("declaration-digital", calculateElapsedTime(eventTime.eventStart, eventTime.eventEnd))

        Future.successful(Right(true))

      case EventType("NOTIFICATION") =>
        //cn => find & update mongo rec only where no cn previously received & calc elapsed time & store count & elapsed time
      ???

    }

  }

  def calculateElapsedTime(start: EventTimeStamp, end: EventTimeStamp): Duration = {
    Duration.between(start.zonedDateTime, end.zonedDateTime)
  }

}
