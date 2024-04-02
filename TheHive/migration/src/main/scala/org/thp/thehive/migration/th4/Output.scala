package org.thp.thehive.migration.th4

import akka.actor.ActorSystem
import akka.actor.typed.{ActorRef, Scheduler}
import akka.stream.Materializer
import com.google.inject.{Guice, Injector => GInjector}
import net.codingwell.scalaguice.{ScalaModule, ScalaMultibinder}
import org.thp.scalligraph._
import org.thp.scalligraph.auth.{AuthContext, AuthContextImpl, UserSrv => UserDB}
import org.thp.scalligraph.janus.JanusDatabase
import org.thp.scalligraph.models._
import org.thp.scalligraph.services._
import org.thp.scalligraph.traversal.Graph
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.thehive.connector.cortex.models.{CortexSchemaDefinition, TheHiveCortexSchemaProvider}
import org.thp.thehive.connector.cortex.services.{ActionSrv, JobSrv}
import org.thp.thehive.dto.v1.InputCustomFieldValue
import org.thp.thehive.migration.IdMapping
import org.thp.thehive.migration.dto._
import org.thp.thehive.models._
import org.thp.thehive.services.AlertOps._
import org.thp.thehive.services.CaseOps._
import org.thp.thehive.services._
import org.thp.thehive.{migration, ClusterSetup}
import play.api.cache.SyncCacheApi
import play.api.cache.ehcache.EhCacheModule
import play.api.inject.guice.GuiceInjector
import play.api.inject.{ApplicationLifecycle, DefaultApplicationLifecycle, Injector}
import play.api.libs.concurrent.{AkkaGuiceSupport, AkkaSchedulerProvider}
import play.api.{Configuration, Environment, Logger}

import javax.inject.{Inject, Provider, Singleton}
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

object Output {

  private def buildApp(configuration: Configuration)(implicit actorSystem: ActorSystem): GInjector =
    Guice
      .createInjector(
        (play.api.inject.guice.GuiceableModule.guiceable(new EhCacheModule).guiced(Environment.simple(), configuration, Set.empty) :+
          new ScalaModule with AkkaGuiceSupport {
            override def configure(): Unit = {
              bind[Configuration].toInstance(configuration)
              bind[ActorSystem].toInstance(actorSystem)
              bind[Scheduler].toProvider[AkkaSchedulerProvider]
              bind[Materializer].toInstance(Materializer(actorSystem))
              bind[ExecutionContext].toInstance(actorSystem.dispatcher)
              bind[Injector].to[GuiceInjector]
              bind[UserDB].to[LocalUserSrv]
              bindActor[DummyActor]("notification-actor")
              bindActor[DummyActor]("config-actor")
              bindActor[DummyActor]("cortex-actor")
              bind[ActorRef[IntegrityCheck.Request]].toProvider[DummyTypedActorProvider[IntegrityCheck.Request]]
              bind[ActorRef[CaseNumberActor.Request]].toProvider[CaseNumberActorProvider]
              val integrityCheckOpsBindings = ScalaMultibinder.newSetBinder[IntegrityCheck](binder)
              integrityCheckOpsBindings.addBinding.to[AlertIntegrityCheck]
              integrityCheckOpsBindings.addBinding.to[CaseIntegrityCheck]
              integrityCheckOpsBindings.addBinding.to[CaseTemplateIntegrityCheck]
              integrityCheckOpsBindings.addBinding.to[CustomFieldIntegrityCheck]
              integrityCheckOpsBindings.addBinding.to[DataIntegrityCheck]
              integrityCheckOpsBindings.addBinding.to[ImpactStatusIntegrityCheck]
              integrityCheckOpsBindings.addBinding.to[LogIntegrityCheck]
              integrityCheckOpsBindings.addBinding.to[ObservableIntegrityCheck]
              integrityCheckOpsBindings.addBinding.to[ObservableTypeIntegrityCheck]
              integrityCheckOpsBindings.addBinding.to[OrganisationIntegrityCheck]
              integrityCheckOpsBindings.addBinding.to[ProfileIntegrityCheck]
              integrityCheckOpsBindings.addBinding.to[ResolutionStatusIntegrityCheck]
              integrityCheckOpsBindings.addBinding.to[TagIntegrityCheck]
              integrityCheckOpsBindings.addBinding.to[TaskIntegrityCheck]
              integrityCheckOpsBindings.addBinding.to[UserIntegrityCheck]
              integrityCheckOpsBindings.addBinding.to[RoleIntegrityCheck]

              val schemaBindings = ScalaMultibinder.newSetBinder[UpdatableSchema](binder)
              schemaBindings.addBinding.to[TheHiveSchemaDefinition]
              schemaBindings.addBinding.to[CortexSchemaDefinition]
              bind[SingleInstance].toInstance(new SingleInstance(true))

              bind[AuditSrv].to[NoAuditSrv]
              bind[Database].toProvider[JanusDatabaseProvider]
              bind[Environment].toInstance(Environment.simple())
              bind[ApplicationLifecycle].to[DefaultApplicationLifecycle]
              bind[Schema].toProvider[TheHiveCortexSchemaProvider]
              configuration.get[String]("storage.provider") match {
                case "localfs"  => bind(classOf[StorageSrv]).to(classOf[LocalFileSystemStorageSrv])
                case "database" => bind(classOf[StorageSrv]).to(classOf[DatabaseStorageSrv])
                case "hdfs"     => bind(classOf[StorageSrv]).to(classOf[HadoopStorageSrv])
                case "s3"       => bind(classOf[StorageSrv]).to(classOf[S3StorageSrv])
              }
              bind[ClusterSetup].asEagerSingleton()
              ()
            }
          }).asJava
      )

  def apply(configuration: Configuration)(implicit actorSystem: ActorSystem): Output = {
    if (configuration.getOptional[Boolean]("dropDatabase").contains(true)) {
      Logger(getClass).info("Drop database")
      new JanusDatabase(configuration, actorSystem, fullTextIndexAvailable = false).drop()
    }
    buildApp(configuration).getInstance(classOf[Output])
  }
}

@Singleton
class Output @Inject() (
    configuration: Configuration,
    theHiveSchema: TheHiveSchemaDefinition,
    cortexSchema: CortexSchemaDefinition,
    caseSrv: CaseSrv,
    observableSrvProvider: Provider[ObservableSrv],
    dataSrv: DataSrv,
    reportTagSrv: ReportTagSrv,
    userSrv: UserSrv,
//    tagSrv: TagSrv,
    caseTemplateSrv: CaseTemplateSrv,
    organisationSrv: OrganisationSrv,
    observableTypeSrv: ObservableTypeSrv,
    alertSrv: AlertSrv,
    taskSrv: TaskSrv,
    shareSrv: ShareSrv,
    attachmentSrv: AttachmentSrv,
    profileSrv: ProfileSrv,
    logSrv: LogSrv,
    auditSrv: AuditSrv,
    customFieldSrv: CustomFieldSrv,
    impactStatusSrv: ImpactStatusSrv,
    resolutionStatusSrv: ResolutionStatusSrv,
    jobSrv: JobSrv,
    actionSrv: ActionSrv,
    dashboardSrv: DashboardSrv,
    db: Database,
    cache: SyncCacheApi,
    checks: immutable.Set[IntegrityCheck]
) extends migration.Output[Graph] {
  lazy val logger: Logger      = Logger(getClass)
  val resumeMigration: Boolean = configuration.get[Boolean]("resume")
  val defaultUserDomain: String = userSrv
    .defaultUserDomain
    .getOrElse(
      throw BadConfigurationError("Default user domain is empty in configuration. Please add `auth.defaultUserDomain` in your configuration file.")
    )
  val caseNumberShift: Int = configuration.get[Int]("caseNumberShift")
  val observableDataIsIndexed: Boolean = {
    val v = db match {
      case jdb: JanusDatabase => jdb.fieldIsIndexed("data")
      case _                  => false
    }
    logger.info(s"The field data is ${if (v) "" else "not"} indexed")
    v
  }
  lazy val observableSrv: ObservableSrv                                         = observableSrvProvider.get
  private var profiles: TrieMap[String, Profile with Entity]                    = TrieMap.empty
  private var organisations: TrieMap[String, Organisation with Entity]          = TrieMap.empty
  private var users: TrieMap[String, User with Entity]                          = TrieMap.empty
  private var impactStatuses: TrieMap[String, ImpactStatus with Entity]         = TrieMap.empty
  private var resolutionStatuses: TrieMap[String, ResolutionStatus with Entity] = TrieMap.empty
  private var observableTypes: TrieMap[String, ObservableType with Entity]      = TrieMap.empty
  private var customFields: TrieMap[String, CustomField with Entity]            = TrieMap.empty
  private var caseTemplates: TrieMap[String, CaseTemplate with Entity]          = TrieMap.empty

  override def startMigration(): Try[Unit] = {
    implicit val authContext: AuthContext = LocalUserSrv.getSystemAuthContext
    if (resumeMigration)
      db.addSchemaIndexes(theHiveSchema)
        .flatMap(_ => db.addSchemaIndexes(cortexSchema))
        .flatMap { _ =>
          db.roTransaction { implicit graph =>
            profiles ++= profileSrv.startTraversal.toSeq.map(p => p.name -> p)
            organisations ++= organisationSrv.startTraversal.toSeq.map(o => o.name -> o)
            users ++= userSrv.startTraversal.toSeq.map(u => u.name -> u)
            impactStatuses ++= impactStatusSrv.startTraversal.toSeq.map(s => s.value -> s)
            resolutionStatuses ++= resolutionStatusSrv.startTraversal.toSeq.map(s => s.value -> s)
            observableTypes ++= observableTypeSrv.startTraversal.toSeq.map(o => o.name -> o)
            customFields ++= customFieldSrv.startTraversal.toSeq.map(c => c.name -> c)
            caseTemplates ++= caseTemplateSrv.startTraversal.toSeq.map(c => c.name -> c)
          }
          Success(())
        }
    else {
      db.setVersion(theHiveSchema.name, theHiveSchema.operations.lastVersion)
      db.setVersion(cortexSchema.name, cortexSchema.operations.lastVersion)
      db.tryTransaction { implicit graph =>
        profiles ++= Profile.initialValues.flatMap(p => profileSrv.createEntity(p).map(p.name -> _).toOption)
        resolutionStatuses ++= ResolutionStatus.initialValues.flatMap(p => resolutionStatusSrv.createEntity(p).map(p.value -> _).toOption)
        impactStatuses ++= ImpactStatus.initialValues.flatMap(p => impactStatusSrv.createEntity(p).map(p.value -> _).toOption)
        observableTypes ++= ObservableType.initialValues.flatMap(p => observableTypeSrv.createEntity(p).map(p.name -> _).toOption)
        organisations ++= Organisation.initialValues.flatMap(p => organisationSrv.createEntity(p).map(p.name -> _).toOption)
        users ++= User.initialValues.flatMap(p => userSrv.createEntity(p).map(p.login -> _).toOption)
        Success(())
      }
    }
  }

  override def endMigration(): Try[Unit] = {
    /* free memory */
    profiles = null
    organisations = null
    users = null
    impactStatuses = null
    resolutionStatuses = null
    observableTypes = null
    customFields = null
    caseTemplates = null

    import MapMerger._
    db.addSchemaIndexes(theHiveSchema)
      .flatMap(_ => db.addSchemaIndexes(cortexSchema))
      .foreach { _ =>
        if (configuration.get[Boolean]("integrityCheck.enabled"))
          checks.foreach { c =>
            logger.info(s"Running check on ${c.name} ...")
            val desupStats = c match {
              case dc: DedupCheck[_] => dc.dedup(KillSwitch.alwaysOn)
              case _                 => Map.empty[String, Long]
            }
            val globalStats = c match {
              case gc: GlobalCheck[_] => gc.runGlobalCheck(24.hours, KillSwitch.alwaysOn)
              case _                  => Map.empty[String, Long]
            }
            val statsStr = (desupStats <+> globalStats)
              .collect { case (k, v) if v != 0 => s"$k:$v" }
              .mkString(" ")
            if (statsStr.isEmpty) logger.info(s"Check on ${c.name}: no change needed")
            else logger.info(s"Check on ${c.name}: $statsStr")
          }
      }

    Try(db.close())
  }

  implicit class RichTry[A](t: Try[A]) {
    def logFailure(message: String): Unit = t.failed.foreach(error => logger.warn(s"$message: $error"))
  }

  private def updateMetaData(entity: Entity, metaData: MetaData)(implicit graph: Graph): Unit = {
    val vertex = graph.VV(entity._id).head
    UMapping.date.setProperty(vertex, "_createdAt", metaData.createdAt)
    UMapping.date.optional.setProperty(vertex, "_updatedAt", metaData.updatedAt)
  }

  private def withAuthContext[R](userId: String)(body: AuthContext => R): R = {
    val authContext =
      if (userId.startsWith("init@") || userId == "init") LocalUserSrv.getSystemAuthContext
      else if (userId.contains('@')) AuthContextImpl(userId, userId, EntityName("admin"), "mig-request", Permissions.all)
      else AuthContextImpl(s"$userId@$defaultUserDomain", s"$userId@$defaultUserDomain", EntityName("admin"), "mig-request", Permissions.all)
    body(authContext)
  }

//  private def getTag(tagName: String, organisationId: String)(implicit graph: Graph, authContext: AuthContext): Try[Tag with Entity] =
//    cache.getOrElseUpdate(s"tag-$organisationId-$tagName", 10.minutes) {
//      tagSrv.createEntity(Tag(s"_freetags_$organisationId", tagName, None, None, tagSrv.freeTagColour))
//    }

  override def withTx[R](body: Graph => Try[R]): Try[R] = db.tryTransaction(body)

  override def organisationExists(tx: Graph, inputOrganisation: InputOrganisation): Boolean =
    organisations.contains(inputOrganisation.organisation.name)

  private def getOrganisation(organisationName: String): Try[Organisation with Entity] =
    organisations
      .get(organisationName)
      .fold[Try[Organisation with Entity]](Failure(NotFoundError(s"Organisation $organisationName not found")))(Success.apply)

  override def createOrganisation(graph: Graph, inputOrganisation: InputOrganisation): Try[IdMapping] =
    withAuthContext(inputOrganisation.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      logger.debug(s"Create organisation ${inputOrganisation.organisation.name}")
      organisationSrv.create(inputOrganisation.organisation).map { o =>
        updateMetaData(o, inputOrganisation.metaData)
        organisations += (o.name -> o)
        IdMapping(inputOrganisation.metaData.id, o._id)
      }
    }

  override def userExists(graph: Graph, inputUser: InputUser): Boolean = {
    val validLogin =
      if (inputUser.user.login.contains('@')) inputUser.user.login.toLowerCase
      else s"${inputUser.user.login}@$defaultUserDomain".toLowerCase
    users.contains(validLogin)
  }

  private def getUser(login: String): Try[User with Entity] = {
    val validLogin =
      if (login.contains('@')) login.toLowerCase
      else s"$login@$defaultUserDomain".toLowerCase
    users
      .get(if (validLogin.startsWith("init@")) User.system.login else validLogin)
      .fold[Try[User with Entity]](Failure(NotFoundError(s"User $login not found")))(Success.apply)
  }

  override def createUser(graph: Graph, inputUser: InputUser): Try[IdMapping] =
    withAuthContext(inputUser.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      logger.debug(s"Create user ${inputUser.user.login}")
      userSrv.checkUser(inputUser.user).flatMap(userSrv.createEntity).map { createdUser =>
        updateMetaData(createdUser, inputUser.metaData)
        inputUser
          .avatar
          .foreach { inputAttachment =>
            attachmentSrv
              .create(inputAttachment.name, inputAttachment.size, inputAttachment.contentType, inputAttachment.data)
              .flatMap { attachment =>
                userSrv.setAvatar(createdUser, attachment)
              }
              .logFailure(s"Unable to set avatar to user ${createdUser.login}")
          }
        inputUser.organisations.foreach {
          case (organisationName, profileName) =>
            (for {
              organisation <- getOrganisation(organisationName)
              profile      <- getProfile(profileName)
              _            <- userSrv.addUserToOrganisation(createdUser, organisation, profile)
            } yield ()).logFailure(s"Unable to put user ${createdUser.login} in organisation $organisationName with profile $profileName")
        }
        users += (createdUser.login -> createdUser)
        IdMapping(inputUser.metaData.id, createdUser._id)
      }
    }

  override def customFieldExists(graph: Graph, inputCustomField: InputCustomField): Boolean =
    customFields.contains(inputCustomField.customField.name)

  private def getCustomField(name: String): Try[CustomField with Entity] =
    customFields.get(name).fold[Try[CustomField with Entity]](Failure(NotFoundError(s"Custom field $name not found")))(Success.apply)

  override def createCustomField(graph: Graph, inputCustomField: InputCustomField): Try[IdMapping] =
    withAuthContext(inputCustomField.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      logger.debug(s"Create custom field ${inputCustomField.customField.name}")
      customFieldSrv.create(inputCustomField.customField).map { cf =>
        updateMetaData(cf, inputCustomField.metaData)
        customFields += (cf.name -> cf)
        IdMapping(inputCustomField.customField.name, cf._id)
      }
    }

  override def observableTypeExists(graph: Graph, inputObservableType: InputObservableType): Boolean =
    observableTypes.contains(inputObservableType.observableType.name)

  private def getObservableType(typeName: String)(implicit graph: Graph, authContext: AuthContext): Try[ObservableType with Entity] =
    observableTypes
      .get(typeName)
      .fold[Try[ObservableType with Entity]] {
        observableTypeSrv.createEntity(ObservableType(typeName, isAttachment = false)).map { ot =>
          observableTypes += (typeName -> ot)
          ot
        }
      }(Success.apply)

  override def createObservableTypes(graph: Graph, inputObservableType: InputObservableType): Try[IdMapping] =
    withAuthContext(inputObservableType.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      logger.debug(s"Create observable types ${inputObservableType.observableType.name}")
      observableTypeSrv.create(inputObservableType.observableType).map { ot =>
        updateMetaData(ot, inputObservableType.metaData)
        observableTypes += (ot.name -> ot)
        IdMapping(inputObservableType.observableType.name, ot._id)
      }
    }

  override def profileExists(graph: Graph, inputProfile: InputProfile): Boolean = profiles.contains(inputProfile.profile.name)

  private def getProfile(profileName: String)(implicit graph: Graph, authContext: AuthContext): Try[Profile with Entity] =
    profiles
      .get(profileName)
      .fold[Try[Profile with Entity]] {
        profileSrv.createEntity(Profile(profileName, Set.empty)).map { p =>
          profiles += (profileName -> p)
          p
        }
      }(Success.apply)

  override def createProfile(graph: Graph, inputProfile: InputProfile): Try[IdMapping] =
    withAuthContext(inputProfile.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      logger.debug(s"Create profile ${inputProfile.profile.name}")
      profileSrv.create(inputProfile.profile).map { profile =>
        updateMetaData(profile, inputProfile.metaData)
        profiles += (profile.name -> profile)
        IdMapping(inputProfile.profile.name, profile._id)
      }
    }

  override def impactStatusExists(graph: Graph, inputImpactStatus: InputImpactStatus): Boolean =
    impactStatuses.contains(inputImpactStatus.impactStatus.value)

  private def getImpactStatus(name: String)(implicit graph: Graph, authContext: AuthContext): Try[ImpactStatus with Entity] =
    impactStatuses
      .get(name)
      .fold[Try[ImpactStatus with Entity]] {
        impactStatusSrv.createEntity(ImpactStatus(name)).map { is =>
          impactStatuses += (name -> is)
          is
        }
      }(Success.apply)

  override def createImpactStatus(graph: Graph, inputImpactStatus: InputImpactStatus): Try[IdMapping] =
    withAuthContext(inputImpactStatus.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      logger.debug(s"Create impact status ${inputImpactStatus.impactStatus.value}")
      impactStatusSrv.create(inputImpactStatus.impactStatus).map { status =>
        updateMetaData(status, inputImpactStatus.metaData)
        impactStatuses += (status.value -> status)
        IdMapping(inputImpactStatus.impactStatus.value, status._id)
      }
    }

  override def resolutionStatusExists(graph: Graph, inputResolutionStatus: InputResolutionStatus): Boolean =
    resolutionStatuses.contains(inputResolutionStatus.resolutionStatus.value)

  private def getResolutionStatus(name: String)(implicit graph: Graph, authContext: AuthContext): Try[ResolutionStatus with Entity] =
    resolutionStatuses
      .get(name)
      .fold[Try[ResolutionStatus with Entity]] {
        resolutionStatusSrv.createEntity(ResolutionStatus(name)).map { rs =>
          resolutionStatuses += (name -> rs)
          rs
        }
      }(Success.apply)

  override def createResolutionStatus(graph: Graph, inputResolutionStatus: InputResolutionStatus): Try[IdMapping] =
    withAuthContext(inputResolutionStatus.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      logger.debug(s"Create resolution status ${inputResolutionStatus.resolutionStatus.value}")
      resolutionStatusSrv
        .create(inputResolutionStatus.resolutionStatus)
        .map { status =>
          updateMetaData(status, inputResolutionStatus.metaData)
          resolutionStatuses += (status.value -> status)
          IdMapping(inputResolutionStatus.resolutionStatus.value, status._id)
        }
    }

  override def caseTemplateExists(graph: Graph, inputCaseTemplate: InputCaseTemplate): Boolean =
    caseTemplates.contains(inputCaseTemplate.caseTemplate.name)

  private def getCaseTemplate(name: String): Option[CaseTemplate with Entity] = caseTemplates.get(name)

  override def createCaseTemplate(graph: Graph, inputCaseTemplate: InputCaseTemplate): Try[IdMapping] =
    withAuthContext(inputCaseTemplate.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      logger.debug(s"Create case template ${inputCaseTemplate.caseTemplate.name}")
      for {
        organisation        <- getOrganisation(inputCaseTemplate.organisation)
        createdCaseTemplate <- caseTemplateSrv.createEntity(inputCaseTemplate.caseTemplate)
        _                   <- caseTemplateSrv.caseTemplateOrganisationSrv.create(CaseTemplateOrganisation(), createdCaseTemplate, organisation)
//        _ <-
//          inputCaseTemplate
//            .caseTemplate
//            .tags
//            .toTry(
//              getTag(_, organisation._id.value).flatMap(t => caseTemplateSrv.caseTemplateTagSrv.create(CaseTemplateTag(), createdCaseTemplate, t))
//            )
        _ = updateMetaData(createdCaseTemplate, inputCaseTemplate.metaData)
        _ = inputCaseTemplate.customFields.foreach {
          case InputCustomFieldValue(name, value, order) =>
            (for {
              cf  <- getCustomField(name)
              ccf <- CustomFieldType.map(cf.`type`).setValue(CaseTemplateCustomField(order = order), value)
              _   <- caseTemplateSrv.caseTemplateCustomFieldSrv.create(ccf, createdCaseTemplate, cf)
            } yield ()).logFailure(s"Unable to set custom field $name=${value.getOrElse("<not set>")}")
        }
        _ = caseTemplates += (inputCaseTemplate.caseTemplate.name -> createdCaseTemplate)
      } yield IdMapping(inputCaseTemplate.metaData.id, createdCaseTemplate._id)
    }

  override def createCaseTemplateTask(graph: Graph, caseTemplateId: EntityId, inputTask: InputTask): Try[IdMapping] =
    withAuthContext(inputTask.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      import CaseTemplateOps._
      logger.debug(s"Create task ${inputTask.task.title} in case template $caseTemplateId")
      val assignee = inputTask.task.assignee.flatMap(u => getUser(u).toOption)
      for {
        (caseTemplate, organisationIds) <-
          caseTemplateSrv.getByIds(caseTemplateId).project(_.by.by(_.organisation._id.fold)).getOrFail("CaseTemplate")
        richTask <- taskSrv.create(inputTask.task.copy(relatedId = caseTemplate._id, organisationIds = organisationIds.toSet), assignee)
        _        <- caseTemplateSrv.caseTemplateTaskSrv.create(CaseTemplateTask(), caseTemplate, richTask.task)
        _ = updateMetaData(richTask.task, inputTask.metaData)
      } yield IdMapping(inputTask.metaData.id, richTask._id)
    }

  override def caseExists(graph: Graph, inputCase: InputCase): Boolean =
    if (!resumeMigration) false
    else
      db.roTransaction { implicit graph =>
        caseSrv.startTraversal.getByNumber(inputCase.`case`.number + caseNumberShift).exists
      }

  private def getCase(caseId: EntityId)(implicit graph: Graph): Try[Case with Entity] =
    cache
      .get[Case with Entity](s"case-$caseId")
      .fold {
        caseSrv.getByIds(caseId).getOrFail("Case").map { c =>
          cache.set(s"case-$caseId", c, 5.minutes)
          c
        }
      }(Success(_))

  override def createCase(graph: Graph, inputCase: InputCase): Try[IdMapping] =
    withAuthContext(inputCase.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      logger.debug(s"Create case #${inputCase.`case`.number + caseNumberShift}")
      val organisationIds = inputCase
        .organisations
        .flatMap {
          case (orgName, _) => getOrganisation(orgName).map(_._id).toOption
        }
        .toSet
      val assignee = inputCase
        .`case`
        .assignee
        .flatMap(getUser(_).toOption)
      val caseTemplate = inputCase
        .`case`
        .caseTemplate
        .flatMap(getCaseTemplate)
      val resolutionStatus = inputCase
        .`case`
        .resolutionStatus
        .flatMap(getResolutionStatus(_).toOption)
      val impactStatus = inputCase
        .`case`
        .impactStatus
        .flatMap(getImpactStatus(_).toOption)
      val `case` = inputCase
        .`case`
        .copy(
          assignee = assignee.map(_.login),
          organisationIds = organisationIds,
          caseTemplate = caseTemplate.map(_.name),
          impactStatus = impactStatus.map(_.value),
          resolutionStatus = resolutionStatus.map(_.value),
          number = inputCase.`case`.number + caseNumberShift
        )
      caseSrv.createEntity(`case`).map { createdCase =>
        updateMetaData(createdCase, inputCase.metaData)
        cache.set(s"case-${createdCase._id}", createdCase, 5.minutes)
        assignee
          .foreach { user =>
            caseSrv
              .caseUserSrv
              .create(CaseUser(), createdCase, user)
              .logFailure(s"Unable to assign case #${createdCase.number} to ${user.login}")
          }
        caseTemplate
          .foreach { ct =>
            caseSrv
              .caseCaseTemplateSrv
              .create(CaseCaseTemplate(), createdCase, ct)
              .logFailure(s"Unable to set case template ${ct.name} to case #${createdCase.number}")
          }
//        inputCase.`case`.tags.foreach { tagName =>
//          getTag(tagName, organisationIds.head.value)
//            .flatMap(tag => caseSrv.caseTagSrv.create(CaseTag(), createdCase, tag))
//            .logFailure(s"Unable to add tag $tagName to case #${createdCase.number}")
//        }
        inputCase.customFields.foreach {
          case (name, value) => // TODO Add order
            getCustomField(name)
              .flatMap { cf =>
                CustomFieldType
                  .map(cf.`type`)
                  .setValue(CaseCustomField(), value)
                  .flatMap(ccf => caseSrv.caseCustomFieldSrv.create(ccf, createdCase, cf))
              }
              .logFailure(s"Unable to set custom field $name=${value.getOrElse("<not set>")} to case #${createdCase.number}")
        }
        inputCase.organisations.foldLeft(false) {
          case (ownerSet, (organisationName, profileName)) =>
            val owner = profileName == Profile.orgAdmin.name && !ownerSet
            val shared = for {
              organisation <- getOrganisation(organisationName)
              profile      <- getProfile(profileName)
              _            <- shareSrv.shareCase(owner, createdCase, organisation, profile)
            } yield ()
            shared.logFailure(s"Unable to share case #${createdCase.number} with organisation $organisationName, profile $profileName")
            ownerSet || owner
        }
        resolutionStatus
          .foreach { resolutionStatus =>
            caseSrv
              .caseResolutionStatusSrv
              .create(CaseResolutionStatus(), createdCase, resolutionStatus)
              .logFailure(s"Unable to set resolution status $resolutionStatus to case #${createdCase.number}")
          }
        impactStatus
          .foreach { impactStatus =>
            caseSrv
              .caseImpactStatusSrv
              .create(CaseImpactStatus(), createdCase, impactStatus)
              .logFailure(s"Unable to set impact status $impactStatus to case #${createdCase.number}")
          }

        IdMapping(inputCase.metaData.id, createdCase._id)
      }
    }

  override def createCaseTask(graph: Graph, caseId: EntityId, inputTask: InputTask): Try[IdMapping] =
    withAuthContext(inputTask.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      logger.debug(s"Create task ${inputTask.task.title} in case $caseId")
      val assignee      = inputTask.owner.flatMap(getUser(_).toOption)
      val organisations = inputTask.organisations.flatMap(getOrganisation(_).toOption)
      for {
        richTask <- taskSrv.create(inputTask.task.copy(relatedId = caseId, organisationIds = organisations.map(_._id)), assignee)
        _ = cache.set(s"task-${richTask._id}", richTask.task, 1.minute)
        _ = updateMetaData(richTask.task, inputTask.metaData)
        case0 <- getCase(caseId)
        _     <- organisations.toTry(o => shareSrv.shareTask(richTask, case0, o._id))
      } yield IdMapping(inputTask.metaData.id, richTask._id)
    }

  private def getTask(taskId: EntityId)(implicit graph: Graph): Try[Task with Entity] =
    cache
      .get[Task with Entity](s"task-$taskId")
      .fold {
        taskSrv.getOrFail(taskId).map { t =>
          cache.set(s"task-$taskId", t, 1.minute)
          t
        }
      }(Success(_))

  override def createCaseTaskLog(graph: Graph, taskId: EntityId, inputLog: InputLog): Try[IdMapping] =
    withAuthContext(inputLog.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      for {
        task <- getTask(taskId)
        _ = logger.debug(s"Create log in task ${task.title}")
        log <- logSrv.createEntity(inputLog.log.copy(taskId = task._id, organisationIds = task.organisationIds))
        _ = updateMetaData(log, inputLog.metaData)
        _ <- logSrv.taskLogSrv.create(TaskLog(), task, log)
        _ <- inputLog.attachments.toTry { inputAttachment =>
          attachmentSrv.create(inputAttachment.name, inputAttachment.size, inputAttachment.contentType, inputAttachment.data).flatMap { attachment =>
            logSrv.logAttachmentSrv.create(LogAttachment(), log, attachment)
          }
        }
      } yield IdMapping(inputLog.metaData.id, log._id)
    }

  private def getData(value: String)(implicit graph: Graph, authContext: AuthContext): Try[Data with Entity] = {
    val (dataOrHash, fullData) = UseHashToIndex.hashToIndex(value).fold[(String, Option[String])](value -> None)(_ -> Some(value))
    if (observableDataIsIndexed) dataSrv.create(Data(dataOrHash, fullData))
    else dataSrv.createEntity(Data(dataOrHash, fullData))
  }

  private def createSimpleObservable(observable: Observable, observableType: ObservableType with Entity, dataValue: String)(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[Observable with Entity] =
    for {
      data <- getData(dataValue)
      _ <-
        if (observableType.isAttachment) Failure(BadRequestError("A attachment observable doesn't accept string value"))
        else Success(())
      createdObservable <- observableSrv.createEntity(observable.copy(data = Some(dataValue)))
      _                 <- observableSrv.observableDataSrv.create(ObservableData(), createdObservable, data)
    } yield createdObservable

  private def createAttachmentObservable(
      observable: Observable,
      observableType: ObservableType with Entity,
      inputAttachment: InputAttachment
  )(implicit graph: Graph, authContext: AuthContext): Try[Observable with Entity] =
    for {
      attachment <- attachmentSrv.create(inputAttachment.name, inputAttachment.size, inputAttachment.contentType, inputAttachment.data)
      _ <-
        if (!observableType.isAttachment) Failure(BadRequestError("A text observable doesn't accept attachment"))
        else Success(())
      createdObservable <- observableSrv.createEntity(observable.copy(data = None))
      _                 <- observableSrv.observableAttachmentSrv.create(ObservableAttachment(), createdObservable, attachment)
    } yield createdObservable

  private def createObservable(relatedId: EntityId, inputObservable: InputObservable, organisationIds: Set[EntityId])(implicit
      graph: Graph,
      authContext: AuthContext
  ) =
    for {
      observableType <- getObservableType(inputObservable.observable.dataType)
      observable <-
        inputObservable
          .dataOrAttachment
          .fold(
            data =>
              createSimpleObservable(
                inputObservable.observable.copy(organisationIds = organisationIds, relatedId = relatedId),
                observableType,
                data
              ),
            attachment =>
              createAttachmentObservable(
                inputObservable.observable.copy(organisationIds = organisationIds, relatedId = relatedId),
                observableType,
                attachment
              )
          )
      _ = updateMetaData(observable, inputObservable.metaData)
//      _ = inputObservable.observable.tags.foreach { tagName =>
//        getTag(tagName, organisationIds.head.value)
//          .foreach(tag => observableSrv.observableTagSrv.create(ObservableTag(), observable, tag))
//      }
    } yield observable

  private def getShare(caseId: EntityId, organisationId: EntityId)(implicit graph: Graph): Try[Share with Entity] =
    cache
      .get[Share with Entity](s"share-$caseId-$organisationId")
      .fold {
        import org.thp.thehive.services.CaseOps._
        caseSrv
          .getByIds(caseId)
          .share(organisationId)
          .getOrFail("Share")
          .map { s =>
            cache.set(s"share-$caseId-$organisationId", s, 5.minutes)
            s
          }
      }(Success(_))

  override def createCaseObservable(graph: Graph, caseId: EntityId, inputObservable: InputObservable): Try[IdMapping] =
    withAuthContext(inputObservable.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      logger.debug(s"Create observable ${inputObservable.dataOrAttachment.fold(identity, _.name)} in case $caseId")
      for {
        organisationIds <- inputObservable.organisations.toTry(getOrganisation).map(_.map(_._id))
        observable      <- createObservable(caseId, inputObservable, organisationIds.toSet)
        _               <- reportTagSrv.updateTags(observable, inputObservable.reportTags)
        _ = organisationIds.toTry { o =>
          getShare(caseId, o)
            .flatMap(share => shareSrv.shareObservableSrv.create(ShareObservable(), share, observable))
        }
      } yield IdMapping(inputObservable.metaData.id, observable._id)
    }

  override def createJob(graph: Graph, observableId: EntityId, inputJob: InputJob): Try[IdMapping] =
    withAuthContext(inputJob.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      logger.debug(s"Create job ${inputJob.job.cortexId}:${inputJob.job.workerName}:${inputJob.job.cortexJobId}")
      for {
        observable <- observableSrv.getOrFail(observableId)
        job        <- jobSrv.create(inputJob.job, observable)
        _ = updateMetaData(job.job, inputJob.metaData)
      } yield IdMapping(inputJob.metaData.id, job._id)
    }

  override def createJobObservable(graph: Graph, jobId: EntityId, inputObservable: InputObservable): Try[IdMapping] =
    withAuthContext(inputObservable.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      logger.debug(s"Create observable ${inputObservable.dataOrAttachment.fold(identity, _.name)} in job $jobId")
      for {
        organisations <- inputObservable.organisations.toTry(getOrganisation)
        observable    <- createObservable(jobId, inputObservable, organisations.map(_._id).toSet)
        job           <- jobSrv.getOrFail(jobId)
        _             <- jobSrv.addObservable(job, observable)
      } yield IdMapping(inputObservable.metaData.id, observable._id)
    }

  override def alertExists(graph: Graph, inputAlert: InputAlert): Boolean =
    if (!resumeMigration) false
    else
      db.roTransaction { implicit graph =>
        alertSrv.startTraversal.getBySourceId(inputAlert.alert.`type`, inputAlert.alert.source, inputAlert.alert.sourceRef).exists
      }

  override def createAlert(graph: Graph, inputAlert: InputAlert): Try[IdMapping] =
    withAuthContext(inputAlert.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      logger.debug(s"Create alert ${inputAlert.alert.`type`}:${inputAlert.alert.source}:${inputAlert.alert.sourceRef}")
      val `case` = inputAlert.caseId.flatMap(c => getCase(EntityId.read(c)).toOption)

      for {
        organisation <- getOrganisation(inputAlert.organisation)
        createdAlert <- alertSrv.createEntity(inputAlert.alert.copy(organisationId = organisation._id, caseId = `case`.fold(EntityId.empty)(_._id)))
        _            <- `case`.map(alertSrv.alertCaseSrv.create(AlertCase(), createdAlert, _)).flip
//        tags = inputAlert.alert.tags.flatMap(getTag(_, organisation._id.value).toOption)
        _ = cache.set(s"alert-${createdAlert._id}", createdAlert, 5.minutes)
        _ = updateMetaData(createdAlert, inputAlert.metaData)
        _ <- alertSrv.alertOrganisationSrv.create(AlertOrganisation(), createdAlert, organisation)
        _ <-
          inputAlert
            .caseTemplate
            .flatMap(getCaseTemplate)
            .map(ct => alertSrv.alertCaseTemplateSrv.create(AlertCaseTemplate(), createdAlert, ct))
            .flip
//        _ = tags.foreach(t => alertSrv.alertTagSrv.create(AlertTag(), createdAlert, t))
        _ = inputAlert.customFields.foreach {
          case (name, value) => // TODO Add order
            getCustomField(name)
              .flatMap { cf =>
                CustomFieldType
                  .map(cf.`type`)
                  .setValue(AlertCustomField(), value)
                  .flatMap(acf => alertSrv.alertCustomFieldSrv.create(acf, createdAlert, cf))
              }
              .logFailure(s"Unable to set custom field $name=${value
                .getOrElse("<not set>")} to alert ${inputAlert.alert.`type`}:${inputAlert.alert.source}:${inputAlert.alert.sourceRef}")
        }
      } yield IdMapping(inputAlert.metaData.id, createdAlert._id)
    }

  override def linkAlertToCase(graph: Graph, alertId: EntityId, caseId: EntityId): Try[Unit] =
    for {
      c <- getCase(caseId)(graph)
      a <- getAlert(alertId)(graph)
      _ <- alertSrv.alertCaseSrv.create(AlertCase(), a, c)(graph, LocalUserSrv.getSystemAuthContext)
    } yield ()

  private def getAlert(alertId: EntityId)(implicit graph: Graph): Try[Alert with Entity] =
    cache
      .get[Alert with Entity](s"alert-$alertId")
      .fold {
        alertSrv.getByIds(alertId).getOrFail("Alert").map { alert =>
          cache.set(s"alert-$alertId", alert, 5.minutes)
          alert
        }
      }(Success(_))

  override def createAlertObservable(graph: Graph, alertId: EntityId, inputObservable: InputObservable): Try[IdMapping] =
    withAuthContext(inputObservable.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      logger.debug(s"Create observable ${inputObservable.dataOrAttachment.fold(identity, _.name)} in alert $alertId")
      for {
        alert      <- getAlert(alertId)
        observable <- createObservable(alert._id, inputObservable, Set(alert.organisationId))
        _          <- alertSrv.alertObservableSrv.create(AlertObservable(), alert, observable)
      } yield IdMapping(inputObservable.metaData.id, observable._id)
    }

  private def getEntity(entityType: String, entityId: EntityId)(implicit graph: Graph): Try[Product with Entity] =
    entityType match {
      case "Task"       => getTask(entityId)
      case "Case"       => getCase(entityId)
      case "Observable" => observableSrv.getOrFail(entityId)
      case "Log"        => logSrv.getOrFail(entityId)
      case "Alert"      => getAlert(entityId)
      case "Job"        => jobSrv.getOrFail(entityId)
      case "Action"     => actionSrv.getOrFail(entityId)
      case _            => Failure(BadRequestError(s"objectType $entityType is not recognised"))
    }

  override def createAction(graph: Graph, objectId: EntityId, inputAction: InputAction): Try[IdMapping] =
    withAuthContext(inputAction.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      logger.debug(
        s"Create action ${inputAction.action.cortexId}:${inputAction.action.workerName}:${inputAction.action.cortexJobId} for ${inputAction.objectType} $objectId"
      )
      for {
        entity <- getEntity(inputAction.objectType, objectId)
        action <- actionSrv.create(inputAction.action, entity)
        _ = updateMetaData(action.action, inputAction.metaData)
      } yield IdMapping(inputAction.metaData.id, action._id)
    }

  override def createAudit(graph: Graph, contextId: EntityId, inputAudit: InputAudit): Try[Unit] =
    withAuthContext(inputAudit.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      logger.debug(s"Create audit ${inputAudit.audit.action} on ${inputAudit.audit.objectType} ${inputAudit.audit.objectId}")
      for {
        obj <- (for {
            t <- inputAudit.audit.objectType
            i <- inputAudit.audit.objectId
          } yield getEntity(t, new EntityId(i))).flip
        ctxType = obj.map(_._label).map {
          case "Alert"                                        => "Alert"
          case "Log" | "Task" | "Observable" | "Case" | "Job" => "Case"
          case "User"                                         => "User"
          case "Action"                                       => "Action" // FIXME
          case other =>
            logger.error(s"Unknown object type: $other")
            other
        }
        context      <- ctxType.map(getEntity(_, contextId)).flip
        user         <- getUser(authContext.userId)
        createdAudit <- auditSrv.createEntity(inputAudit.audit)
        _ = updateMetaData(createdAudit, inputAudit.metaData)
        _ <- auditSrv.auditUserSrv.create(AuditUser(), createdAudit, user)
        _ <- obj.map(auditSrv.auditedSrv.create(Audited(), createdAudit, _)).flip
        _ <- context.map(auditSrv.auditContextSrv.create(AuditContext(), createdAudit, _)).flip
      } yield ()
    }

  def dashboardExists(graph: Graph, inputDashboard: InputDashboard): Boolean =
    if (!resumeMigration) false
    else
      db.roTransaction { implicit graph =>
        dashboardSrv.startTraversal.has(_.title, inputDashboard.dashboard.title).exists
      }

  override def createDashboard(graph: Graph, inputDashboard: InputDashboard): Try[IdMapping] =
    withAuthContext(inputDashboard.metaData.createdBy) { implicit authContext =>
      implicit val g: Graph = graph
      logger.debug(s"Create dashboard ${inputDashboard.dashboard.title}")
      for {
        dashboard <- dashboardSrv.create(inputDashboard.dashboard).map(_.dashboard)
        _         <- inputDashboard.organisation.map { case (org, writable) => dashboardSrv.share(dashboard, EntityName(org), writable) }.flip
        _ = updateMetaData(dashboard, inputDashboard.metaData)
      } yield IdMapping(inputDashboard.metaData.id, dashboard._id)
    }
}
