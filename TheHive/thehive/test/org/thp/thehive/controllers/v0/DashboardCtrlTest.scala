package org.thp.thehive.controllers.v0

import org.thp.scalligraph.models.Database
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.thehive.TestAppBuilder
import org.thp.thehive.dto.v0.OutputDashboard
import org.thp.thehive.services.DashboardSrv
import play.api.libs.json.Json
import play.api.test.{FakeRequest, PlaySpecification}

class DashboardCtrlTest extends PlaySpecification with TestAppBuilder {
  "dashboard controller" should {

    "create a dashboard" in testApp { app =>
      val request = FakeRequest("POST", "/api/dashboard")
        .withHeaders("user" -> "certuser@thehive.local")
        .withJsonBody(Json.parse("""{"title": "title test 1", "description": "desc test 1", "status": "Private", "definition": "{\"items\":[]}"}"""))
      val result = app[DashboardCtrl].create(request)

      status(result) must equalTo(201).updateMessage(s => s"$s\n${contentAsString(result)}")

      val dashboard = contentAsJson(result).as[OutputDashboard]

      dashboard.title shouldEqual "title test 1"
      dashboard.description shouldEqual "desc test 1"
      dashboard.status shouldEqual "Private" pendingUntilFixed "Dashboards rights management ongoing"
      dashboard.definition shouldEqual "{\"items\":[]}"
    }

    "get a dashboard if visible" in testApp { app =>
      val dashboard = app[Database].roTransaction { implicit graph =>
        app[DashboardSrv].startTraversal.has(_.title, "dashboard cert").getOrFail("Dashboard").get
      }

      val request = FakeRequest("GET", s"/api/dashboard/${dashboard._id}")
        .withHeaders("user" -> "certuser@thehive.local")
      val result = app[DashboardCtrl].get(dashboard._id.toString)(request)

      status(result) must equalTo(200).updateMessage(s => s"$s\n${contentAsString(result)}")

      val requestFailed = FakeRequest("GET", s"/api/dashboard/${dashboard._id}")
        .withHeaders("user" -> "socuser@thehive.local")
      val resultFailed = app[DashboardCtrl].get(dashboard._id.toString)(requestFailed)

      status(resultFailed) must equalTo(404).updateMessage(s => s"$s\n${contentAsString(resultFailed)}")
    }

    "update a dashboard" in testApp { app =>
      val dashboard = app[Database].roTransaction { implicit graph =>
        app[DashboardSrv].startTraversal.has(_.title, "dashboard cert").getOrFail("Dashboard").get
      }

      val request = FakeRequest("PATCH", s"/api/dashboard/${dashboard._id}")
        .withHeaders("user" -> "certadmin@thehive.local")
        .withJsonBody(Json.parse("""{"title": "updated", "description": "updated", "status": "Private", "definition": "{}"}"""))
      val result = app[DashboardCtrl].update(dashboard._id.toString)(request)

      status(result) must equalTo(200).updateMessage(s => s"$s\n${contentAsString(result)}")

      val updatedDashboard = contentAsJson(result).as[OutputDashboard]

      updatedDashboard.title shouldEqual "updated"
      updatedDashboard.description shouldEqual "updated"
      updatedDashboard.status shouldEqual "Private"
      updatedDashboard.definition shouldEqual "{}"
    }

    "delete a dashboard" in testApp { app =>
      val dashboard = app[Database].roTransaction { implicit graph =>
        app[DashboardSrv].startTraversal.has(_.title, "dashboard cert").getOrFail("Dashboard").get
      }

      val request = FakeRequest("DELETE", s"/api/dashboard/${dashboard._id}")
        .withHeaders("user" -> "certadmin@thehive.local")
      val result = app[DashboardCtrl].delete(dashboard._id.toString)(request)

      status(result) must equalTo(204).updateMessage(s => s"$s\n${contentAsString(result)}")

      app[Database].roTransaction { implicit graph =>
        app[DashboardSrv].startTraversal.has(_.title, "dashboard cert").exists must beFalse
      }
    }
  }
}
