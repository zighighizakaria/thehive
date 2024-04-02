package org.thp.thehive.controllers.v0

import akka.stream.Materializer
import io.scalaland.chimney.dsl._
import org.thp.scalligraph.models.Database
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.thehive.TestAppBuilder
import org.thp.thehive.controllers.v0.Conversion._
import org.thp.thehive.dto.v0.OutputTask
import org.thp.thehive.models._
import org.thp.thehive.services.TaskOps._
import org.thp.thehive.services.{CaseSrv, TaskSrv}
import play.api.libs.json.Json
import play.api.test.{FakeRequest, PlaySpecification}

import java.util.Date

case class TestTask(
    title: String,
    group: Option[String] = None,
    description: Option[String] = None,
    owner: Option[String] = None,
    status: String,
    flag: Boolean = false,
    startDate: Option[Date] = None,
    endDate: Option[Date] = None,
    order: Int = 0,
    dueDate: Option[Date] = None
)

object TestTask {
  def apply(richTask: RichTask): TestTask = apply(richTask.toValue)

  def apply(outputTask: OutputTask): TestTask =
    outputTask.into[TestTask].transform
}

class TaskCtrlTest extends PlaySpecification with TestAppBuilder {
  "task controller" should {
    "list available tasks and get one task" in testApp { app =>
      val taskId = app[Database].roTransaction { implicit graph =>
        app[TaskSrv].startTraversal.has(_.title, "case 1 task 1")._id.getOrFail("Task").get
      }
      val request    = FakeRequest("GET", s"/api/case/task/$taskId").withHeaders("user" -> "certuser@thehive.local")
      val result     = app[TaskCtrl].get(taskId.toString)(request)
      val resultTask = contentAsJson(result)

      status(result) shouldEqual 200

      val expected = TestTask(
        title = "case 1 task 1",
        description = Some("description task 1"),
        owner = Some("certuser@thehive.local"),
        startDate = None,
        status = "Waiting",
        group = Some("group1"),
        endDate = None,
        dueDate = None
      )

      TestTask(resultTask.as[OutputTask]) must_=== expected
    }

    "patch a task" in testApp { app =>
      val taskId = app[Database].roTransaction { implicit graph =>
        app[TaskSrv].startTraversal.has(_.title, "case 1 task 1")._id.getOrFail("Task").get
      }
      val request = FakeRequest("PATCH", s"/api/case/task/$taskId")
        .withHeaders("user" -> "certuser@thehive.local")
        .withJsonBody(Json.parse("""{"title": "new title task 1", "owner": "certuser@thehive.local", "status": "InProgress"}"""))
      val result = app[TaskCtrl].update(taskId.toString)(request)

      status(result) shouldEqual 200

      val expected = TestTask(
        title = "new title task 1",
        description = Some("description task 1"),
        owner = Some("certuser@thehive.local"),
        startDate = None,
        status = "InProgress",
        group = Some("group1"),
        endDate = None,
        dueDate = None
      )

      val newTask = app[Database]
        .roTransaction { implicit graph =>
          app[TaskSrv].startTraversal.has(_.title, "new title task 1").richTask.getOrFail("Task")
        }
        .map(TestTask.apply)
        .map(_.copy(startDate = None))
      newTask must beASuccessfulTry(expected)
    }

    "create a new task for an existing case" in testApp { app =>
      val request = FakeRequest("POST", "/api/case/1/task?flag=true")
        .withJsonBody(
          Json
            .parse(
              """{
                    "title": "case 1 task",
                    "group": "group1",
                    "description": "description task 1",
                    "status": "Waiting"
                }"""
            )
        )
        .withHeaders("user" -> "certuser@thehive.local")

      val result     = app[TaskCtrl].create("1")(request)
      val resultTask = contentAsJson(result)
      status(result) must beEqualTo(201).updateMessage(s => s"$s\n${contentAsString(result)}")

      val resultTaskOutput = resultTask.as[OutputTask]
      val expected = TestTask(
        title = "case 1 task",
        description = Some("description task 1"),
        owner = None,
        startDate = None,
        flag = true,
        status = "Waiting",
        group = Some("group1"),
        endDate = None,
        dueDate = None
      )

      TestTask(resultTaskOutput) must_=== expected

      val requestGet = FakeRequest("GET", s"/api/case/task/${resultTaskOutput.id}").withHeaders("user" -> "certuser@thehive.local")
      val resultGet  = app[TaskCtrl].get(resultTaskOutput.id)(requestGet)

      status(resultGet) shouldEqual 200
    }

    "unset task owner" in testApp { app =>
      val taskId = app[Database].roTransaction { implicit graph =>
        app[TaskSrv].startTraversal.has(_.title, "case 1 task 1")._id.getOrFail("Task").get
      }
      val request = FakeRequest("PATCH", s"/api/case/task/$taskId")
        .withHeaders("user" -> "certuser@thehive.local")
        .withJsonBody(Json.parse("""{"owner": null}"""))
      val result = app[TaskCtrl].update(taskId.toString)(request)

      status(result) shouldEqual 200

      val newTask = app[Database]
        .roTransaction { implicit graph =>
          app[TaskSrv].startTraversal.has(_.title, "case 1 task 1").richTask.getOrFail("Task")
        }
        .map(TestTask.apply)

      val expected = TestTask(
        title = "case 1 task 1",
        description = Some("description task 1"),
        owner = None,
        startDate = None,
        status = "Waiting",
        group = Some("group1"),
        endDate = None,
        dueDate = None
      )

      newTask must beSuccessfulTry(expected)

    }

    "search tasks in case" in testApp { app =>
      val request = FakeRequest("POST", "/api/case/task/_stats")
        .withHeaders("user" -> "certuser@thehive.local")
        .withJsonBody(Json.parse(s"""{
               "query":{
                 "order": 1
               }
             }"""))
      val result = app[TaskCtrl].search(request)
      val t = TestTask(
        title = "case 1 task 2",
        group = Some("group1"),
        description = Some("description task 2"),
        status = "Waiting",
        flag = true,
        order = 1
      )
      val tasks = contentAsJson(result)(defaultAwaitTimeout, app[Materializer]).as[Seq[OutputTask]]
      tasks.map(TestTask.apply) should contain(t)
    }

    "get tasks stats" in testApp { app =>
      val case1 = app[Database].roTransaction(graph => app[CaseSrv].startTraversal(graph).has(_.title, "case#1").getOrFail("Case"))

      case1 must beSuccessfulTry

      val c = case1.get

      val request = FakeRequest("POST", "/api/case/task/_stats")
        .withHeaders("user" -> "certuser@thehive.local")
        .withJsonBody(
          Json.parse(
            s"""{
                         "query":{
                            "_and":[
                               {
                                  "_parent":{
                                     "_type":"case",
                                     "_query":{
                                        "_id":"${c._id}"
                                     }
                                  }
                               },
                               {
                                  "_not":{
                                     "status":"Cancel"
                                  }
                               }
                            ]
                         },
                         "stats":[
                            {
                               "_agg":"field",
                               "_field":"status",
                               "_select":[
                                  {
                                     "_agg":"count"
                                  }
                               ]
                            },
                            {
                               "_agg":"count"
                            }
                         ]
                      }""".stripMargin
          )
        )
      val result = app[TaskCtrl].stats(request)

      status(result) must equalTo(200)

      contentAsJson(result) shouldEqual Json.parse("""{"count":2,"Waiting":{"count":2}}""")
    }
  }
}
