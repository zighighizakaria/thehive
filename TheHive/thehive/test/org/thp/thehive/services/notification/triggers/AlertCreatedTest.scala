package org.thp.thehive.services.notification.triggers

import java.util.Date

import org.thp.scalligraph.models.Database
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.{EntityIdOrName, EntityName}
import org.thp.thehive.TestAppBuilder
import org.thp.thehive.controllers.v0.AlertCtrl
import org.thp.thehive.dto.v0.{InputAlert, OutputAlert}
import org.thp.thehive.services.{AlertSrv, AuditSrv, OrganisationSrv, UserSrv}
import play.api.libs.json.{JsObject, Json}
import play.api.test.{FakeRequest, PlaySpecification}

class AlertCreatedTest extends PlaySpecification with TestAppBuilder {

  "alert created trigger" should {
    "be properly triggered on alert creation" in testApp { app =>
      app[Database].roTransaction { implicit graph =>
        val request = FakeRequest("POST", "/api/v0/alert")
          .withJsonBody(
            Json
              .toJson(
                InputAlert(
                  `type` = "test",
                  source = "alert_creation_test",
                  sourceRef = "#1",
                  externalLink = None,
                  title = "alert title (create alert test)",
                  description = "alert description (create alert test)",
                  severity = Some(2),
                  date = Some(new Date()),
                  tags = Set("tag1", "tag2"),
                  flag = Some(false),
                  tlp = Some(1),
                  pap = Some(3)
                )
              )
              .as[JsObject]
          )
          .withHeaders("user" -> "certuser@thehive.local")

        val result = app[AlertCtrl].create(request)

        status(result) should equalTo(201)

        val alertOutput = contentAsJson(result).as[OutputAlert]
        val alert       = app[AlertSrv].get(EntityIdOrName(alertOutput.id)).getOrFail("Alert")

        alert must beSuccessfulTry

        val audit = app[AuditSrv].startTraversal.has(_.objectId, alert.get._id.toString).getOrFail("Audit")

        audit must beSuccessfulTry

        val organisation = app[OrganisationSrv].get(EntityName("cert")).getOrFail("Organisation")

        organisation must beSuccessfulTry

        val user2 = app[UserSrv].getOrFail(EntityName("certadmin@thehive.local"))
        val user1 = app[UserSrv].getOrFail(EntityName("certuser@thehive.local"))

        user2 must beSuccessfulTry
        user1 must beSuccessfulTry

        val alertCreated = AlertCreated

        alertCreated.filter(audit.get, Some(alert.get), organisation.get, user1.toOption) must beFalse
        alertCreated.filter(audit.get, Some(alert.get), organisation.get, user2.toOption) must beTrue
      }
    }
  }
}
