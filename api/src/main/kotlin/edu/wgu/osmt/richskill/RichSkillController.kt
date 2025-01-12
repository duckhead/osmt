package edu.wgu.osmt.richskill

import edu.wgu.osmt.HasAllPaginated
import edu.wgu.osmt.RoutePaths
import edu.wgu.osmt.api.GeneralApiException
import edu.wgu.osmt.api.model.ApiSearch
import edu.wgu.osmt.api.model.ApiSearchV2
import edu.wgu.osmt.api.model.ApiSkill
import edu.wgu.osmt.api.model.ApiSkillUpdate
import edu.wgu.osmt.api.model.ApiSkillUpdateMapper
import edu.wgu.osmt.api.model.ApiSkillUpdateV2
import edu.wgu.osmt.api.model.ApiSkillV2
import edu.wgu.osmt.api.model.SkillSortEnum
import edu.wgu.osmt.api.model.SortOrder
import edu.wgu.osmt.auditlog.AuditLog
import edu.wgu.osmt.auditlog.AuditLogRepository
import edu.wgu.osmt.auditlog.AuditLogSortEnum
import edu.wgu.osmt.config.AppConfig
import edu.wgu.osmt.db.PublishStatus
import edu.wgu.osmt.elasticsearch.OffsetPageable
import edu.wgu.osmt.elasticsearch.PaginatedLinks
import edu.wgu.osmt.io.csv.RichSkillCsvExport
import edu.wgu.osmt.io.csv.RichSkillCsvExportV2
import edu.wgu.osmt.keyword.KeywordDao
import edu.wgu.osmt.security.OAuthHelper
import edu.wgu.osmt.task.AppliesToType
import edu.wgu.osmt.task.CreateSkillsTask
import edu.wgu.osmt.task.CreateSkillsTaskV2
import edu.wgu.osmt.task.CsvTask
import edu.wgu.osmt.task.CsvTaskV2
import edu.wgu.osmt.task.ExportSkillsToCsvTask
import edu.wgu.osmt.task.ExportSkillsToCsvTaskV2
import edu.wgu.osmt.task.ExportSkillsToXlsxTask
import edu.wgu.osmt.task.PublishTask
import edu.wgu.osmt.task.PublishTaskV2
import edu.wgu.osmt.task.Task
import edu.wgu.osmt.task.TaskMessageService
import edu.wgu.osmt.task.TaskResult
import edu.wgu.osmt.task.XlsxTask
import org.apache.commons.lang3.StringUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.util.UriComponentsBuilder

@Controller
@Transactional
class RichSkillController @Autowired constructor(
    val richSkillRepository: RichSkillRepository,
    val taskMessageService: TaskMessageService,
    val richSkillEsRepo: RichSkillEsRepo,
    val auditLogRepository: AuditLogRepository,
    val appConfig: AppConfig,
    val oAuthHelper: OAuthHelper
) : HasAllPaginated<RichSkillDoc> {
    override val elasticRepository = richSkillEsRepo
    
    val keywordDao = KeywordDao.Companion
    
    override val allPaginatedPath: String = "${RoutePaths.API_V3}${RoutePaths.SKILLS_LIST}"
    override val sortOrderCompanion = SkillSortEnum.Companion
    
    @Deprecated("Replaced with allPaginatedWithFilters")
    @GetMapping(path = [
        "${RoutePaths.API}${RoutePaths.API_V3}${RoutePaths.SKILLS_LIST}",
    ],
        produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    override fun allPaginated(
        uriComponentsBuilder: UriComponentsBuilder,
        size: Int,
        from: Int,
        status: Array<String>,
        sort: String?,
        @AuthenticationPrincipal user: Jwt?
    ): HttpEntity<List<RichSkillDoc>> {
        if (!appConfig.allowPublicLists && user === null) {
            throw GeneralApiException("Unauthorized", HttpStatus.UNAUTHORIZED)
        }
        return super.allPaginated(uriComponentsBuilder, size, from, status, sort, user)
    }
    
    @GetMapping(path = [
        "${RoutePaths.API}${RoutePaths.API_V2}${RoutePaths.SKILLS_LIST}",
        "${RoutePaths.API}${RoutePaths.UNVERSIONED}${RoutePaths.SKILLS_LIST}"
    ],
        produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun allPaginatedV2(
        uriComponentsBuilder: UriComponentsBuilder,
        size: Int,
        from: Int,
        status: Array<String>,
        sort: String?,
        @AuthenticationPrincipal user: Jwt?
    ): HttpEntity<List<RichSkillDocV2>> {
        if (!appConfig.allowPublicLists && user === null) {
            throw GeneralApiException("Unauthorized", HttpStatus.UNAUTHORIZED)
        }
        val paginated = super.allPaginated(uriComponentsBuilder, size, from, status, sort, user)
        val v2Body = paginated.body?.map { RichSkillDocV2.fromLatest(it) }
        
        return ResponseEntity.status(200).headers(paginated.headers).body(v2Body)
    }
    
    @PostMapping("${RoutePaths.API}${RoutePaths.API_V3}${RoutePaths.SKILLS_FILTER}", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun allPaginatedWithFilters(
        uriComponentsBuilder: UriComponentsBuilder,
        size: Int,
        from: Int,
        status: Array<String>,
        @RequestBody apiSearch: ApiSearch,
        sort: String?,
        @AuthenticationPrincipal user: Jwt?
    ): HttpEntity<List<RichSkillDoc>> {
        val publishStatuses = status.mapNotNull {
            val status = PublishStatus.forApiValue(it)
            if (user == null && (status == PublishStatus.Deleted || status == PublishStatus.Draft)) null else status
        }.toSet()
        val sortEnum: SortOrder = sortOrderCompanion.forValueOrDefault(sort)
        val pageable = OffsetPageable(from, size, sortEnum.sort)
        val searchHits = richSkillEsRepo.byApiSearch(
            apiSearch,
            publishStatuses,
            pageable,
            StringUtils.EMPTY
        )
        val countAllFiltered: Long = searchHits.totalHits
        val responseHeaders = HttpHeaders()
        responseHeaders.add("X-Total-Count", countAllFiltered.toString())
        
        uriComponentsBuilder
            .path("${RoutePaths.API}${RoutePaths.API_V3}${RoutePaths.SKILLS_FILTER}")
            .queryParam(RoutePaths.QueryParams.FROM, from)
            .queryParam(RoutePaths.QueryParams.SIZE, size)
            .queryParam(RoutePaths.QueryParams.SORT, sort)
            .queryParam(RoutePaths.QueryParams.STATUS, status.joinToString(",").toLowerCase())
        
        PaginatedLinks(
            pageable,
            searchHits.totalHits.toInt(),
            uriComponentsBuilder
        ).addToHeaders(responseHeaders)
        
        return ResponseEntity.status(200).headers(responseHeaders)
            .body(searchHits.map { it.content }.toList())
    }
    
    @PostMapping(path = [
        "${RoutePaths.API}${RoutePaths.API_V3}${RoutePaths.SKILLS_CREATE}",
    ],
        produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun createSkills(
        @RequestBody apiSkillUpdates: List<ApiSkillUpdate>,
        @AuthenticationPrincipal user: Jwt?
    ): HttpEntity<TaskResult> {
        val task = CreateSkillsTask(apiSkillUpdates, oAuthHelper.readableUserName(user), oAuthHelper.readableUserIdentifier(user))
        taskMessageService.enqueueJob(TaskMessageService.createSkills, task)
        
        return Task.processingResponse(task)
    }
    
    @PostMapping(path = [
        "${RoutePaths.API}${RoutePaths.API_V2}${RoutePaths.SKILLS_CREATE}",
        "${RoutePaths.API}${RoutePaths.UNVERSIONED}${RoutePaths.SKILLS_CREATE}"
    ],
        produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun createSkillsV2(
        @RequestBody apiSkillUpdates: List<ApiSkillUpdateV2>,
        @AuthenticationPrincipal user: Jwt?
    ): HttpEntity<TaskResult> {
        val task = CreateSkillsTaskV2(apiSkillUpdates, oAuthHelper.readableUserName(user), oAuthHelper.readableUserIdentifier(user))
        taskMessageService.enqueueJob(TaskMessageService.createSkills, task)
        
        return Task.processingResponse(task)
    }
    
    @GetMapping("${RoutePaths.API}${RoutePaths.API_V3}${RoutePaths.SKILL_DETAIL}", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun byUUID(
        @PathVariable uuid: String,
        @AuthenticationPrincipal user: Jwt?
    ): ApiSkill? {
        
        return richSkillRepository.findByUUID(uuid)?.let {
            if (user == null && it.publishStatus() == PublishStatus.Unarchived) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND)
            }
            
            ApiSkill.fromDao(it, appConfig)
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }
    
    @GetMapping(path = [
        "${RoutePaths.API}${RoutePaths.API_V2}${RoutePaths.SKILL_DETAIL}",
        "${RoutePaths.API}${RoutePaths.UNVERSIONED}${RoutePaths.SKILL_DETAIL}",
    ],
        produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun byUUIDV2(
        @PathVariable uuid: String,
        @AuthenticationPrincipal user: Jwt?
    ): ApiSkill? {
        
        return byUUID(uuid, user)?.let { ApiSkillV2.fromLatest(it, appConfig) }
    }
    
    @RequestMapping("${RoutePaths.API}${RoutePaths.API_V3}${RoutePaths.SKILL_DETAIL}", produces = [MediaType.TEXT_HTML_VALUE])
    fun byUUIDHtmlView(
        @PathVariable uuid: String,
        @AuthenticationPrincipal user: Jwt?
    ): String {
        
        return richSkillRepository.findByUUID(uuid)?.let {
            if (user == null && it.publishStatus() == PublishStatus.Unarchived) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND)
            }
            
            "forward:/v3/skills/$uuid"
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }
    
    @RequestMapping(path = [
        "${RoutePaths.API}${RoutePaths.API_V2}${RoutePaths.SKILL_DETAIL}",
        "${RoutePaths.API}${RoutePaths.UNVERSIONED}${RoutePaths.SKILL_DETAIL}"
    ],
        produces = [MediaType.TEXT_HTML_VALUE])
    fun byUUIDHtmlViewV2(
        @PathVariable uuid: String,
        @AuthenticationPrincipal user: Jwt?
    ): String {
        
        return richSkillRepository.findByUUID(uuid)?.let {
            if (user == null && it.publishStatus() == PublishStatus.Unarchived) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND)
            }
            
            "forward:/v2/skills/$uuid"
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }
    
    @RequestMapping(path = [
        "${RoutePaths.API}/{apiVersion}${RoutePaths.COLLECTION_SKILLS_UPDATE}"
    ],
        produces = ["text/csv"])
    fun byUUIDCsvView(
        @PathVariable(name = "apiVersion", required = false) apiVersion: String?,
        @PathVariable uuid: String,
        @AuthenticationPrincipal user: Jwt?
    ): HttpEntity<*> {
        
        return richSkillRepository.findByUUID(uuid)?.let {
            if (user == null && it.publishStatus() == PublishStatus.Unarchived) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND)
            }
            val skill = it.toModel()
            val collections = it.collections.map { it.toModel() }.toSet()
            val result = if (RoutePaths.API_V3 == "/${apiVersion}") {
                RichSkillCsvExport(appConfig).toCsv(listOf(RichSkillAndCollections(skill, collections)))
            } else if (RoutePaths.API_V2 == "/${apiVersion}" || RoutePaths.UNVERSIONED == apiVersion) {
                RichSkillCsvExportV2(appConfig).toCsv(listOf(RichSkillAndCollections(skill, collections)))
            } else {
                throw ResponseStatusException(HttpStatus.NOT_FOUND)
            }
            val responseHeaders = HttpHeaders()
            responseHeaders.add("Content-Type", "text/csv")
            
            return ResponseEntity.ok().headers(responseHeaders).body(result)
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }
    
    @PostMapping("${RoutePaths.API}${RoutePaths.API_V3}${RoutePaths.SKILL_UPDATE}", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun updateSkill(
        @PathVariable uuid: String,
        @RequestBody skillUpdate: ApiSkillUpdate,
        @AuthenticationPrincipal user: Jwt?
    ): ApiSkill {
        if (oAuthHelper.hasRole(appConfig.roleCurator) && !oAuthHelper.isArchiveRelated(skillUpdate.publishStatus)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        }
        
        val existingSkill = richSkillRepository.findByUUID(uuid)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        
        val updatedSkill =
            richSkillRepository.updateFromApi(existingSkill.id.value, skillUpdate, oAuthHelper.readableUserName(user), oAuthHelper.readableUserIdentifier(user))
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        
        return ApiSkill.fromDao(updatedSkill, appConfig)
    }
    
    @PostMapping(path = [
        "${RoutePaths.API}${RoutePaths.API_V2}${RoutePaths.SKILL_UPDATE}",
        "${RoutePaths.API}${RoutePaths.UNVERSIONED}${RoutePaths.SKILL_UPDATE}",
    ], produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun updateSkillV2(
        @PathVariable uuid: String,
        @RequestBody skillUpdate: ApiSkillUpdateV2,
        @AuthenticationPrincipal user: Jwt?
    ): ApiSkill {
        
        return ApiSkillV2.fromLatest(
            updateSkill(
                uuid,
                ApiSkillUpdateMapper.mapApiSkillUpdateV2ToApiSkillUpdate(
                    skillUpdate,
                    uuid,
                    richSkillRepository
                ),
                user
            ), appConfig
        )
    }
    
    @PostMapping(path = [
        "${RoutePaths.API}${RoutePaths.API_V3}${RoutePaths.SKILL_PUBLISH}",
    ],
        produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun publishSkills(
        @RequestBody search: ApiSearch,
        @RequestParam(
            required = false,
            defaultValue = "Published"
        ) newStatus: String,
        @RequestParam(
            required = false,
            defaultValue = PublishStatus.DEFAULT_API_PUBLISH_STATUS_SET
        ) filterByStatus: List<String>,
        @RequestParam(
            required = false,
            defaultValue = ""
        ) collectionUuid: String,
        @AuthenticationPrincipal user: Jwt?
    ): HttpEntity<TaskResult> {
        val filterStatuses = filterByStatus.mapNotNull { PublishStatus.forApiValue(it) }.toSet()
        val publishStatus = PublishStatus.forApiValue(newStatus)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        val task = PublishTask(
            AppliesToType.Skill,
            search,
            filterByStatus = filterStatuses,
            publishStatus = publishStatus,
            userString = oAuthHelper.readableUserName(user),
            collectionUuid = if (collectionUuid.isNullOrBlank()) null else collectionUuid
        )
        taskMessageService.enqueueJob(TaskMessageService.publishSkills, task)
        
        return Task.processingResponse(task)
    }
    
    @PostMapping(path = [
        "${RoutePaths.API}${RoutePaths.API_V2}${RoutePaths.SKILL_PUBLISH}",
        "${RoutePaths.API}${RoutePaths.UNVERSIONED}${RoutePaths.SKILL_PUBLISH}"
    ],
        produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun publishSkillsV2(
        @RequestBody search: ApiSearchV2,
        @RequestParam(
            required = false,
            defaultValue = "Published"
        ) newStatus: String,
        @RequestParam(
            required = false,
            defaultValue = PublishStatus.DEFAULT_API_PUBLISH_STATUS_SET
        ) filterByStatus: List<String>,
        @RequestParam(
            required = false,
            defaultValue = ""
        ) collectionUuid: String,
        @AuthenticationPrincipal user: Jwt?
    ): HttpEntity<TaskResult> {
        val filterStatuses = filterByStatus.mapNotNull { PublishStatus.forApiValue(it) }.toSet()
        val publishStatus = PublishStatus.forApiValue(newStatus)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        val task = PublishTaskV2(
            AppliesToType.Skill,
            search,
            filterByStatus = filterStatuses,
            publishStatus = publishStatus,
            userString = oAuthHelper.readableUserName(user),
            collectionUuid = if (collectionUuid.isNullOrBlank()) null else collectionUuid
        )
        taskMessageService.enqueueJob(TaskMessageService.publishSkills, task)
        
        return Task.processingResponse(task)
    }
    
    @GetMapping(path = [
        "${RoutePaths.API}${RoutePaths.API_V2}${RoutePaths.SKILL_AUDIT_LOG}",
        "${RoutePaths.API}${RoutePaths.API_V3}${RoutePaths.SKILL_AUDIT_LOG}",
        "${RoutePaths.API}${RoutePaths.UNVERSIONED}${RoutePaths.SKILL_AUDIT_LOG}"
    ],
        produces = [MediaType.APPLICATION_JSON_VALUE])
    fun skillAuditLog(
        @PathVariable uuid: String
    ): HttpEntity<List<AuditLog>> {
        val pageable = OffsetPageable(0, Int.MAX_VALUE, AuditLogSortEnum.forValueOrDefault(AuditLogSortEnum.DateDesc.apiValue).sort)
        val skill = richSkillRepository.findByUUID(uuid)
        val sizedIterable = auditLogRepository.findByTableAndId(RichSkillDescriptorTable.tableName, entityId = skill!!.id.value, offsetPageable = pageable)
        
        return ResponseEntity.status(200).body(sizedIterable.toList().map { it.toModel() })
    }
    
    @Transactional(readOnly = true)
    @GetMapping(path = [
        "${RoutePaths.API}${RoutePaths.API_V3}${RoutePaths.EXPORT_LIBRARY_CSV}",
    ],
        produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun exportLibraryCsv(
        @AuthenticationPrincipal user: Jwt?
    ): HttpEntity<TaskResult> {
        if (!appConfig.allowPublicSearching && user === null) {
            throw GeneralApiException("Unauthorized", HttpStatus.UNAUTHORIZED)
        }
        if (!oAuthHelper.hasRole(appConfig.roleAdmin)) {
            throw GeneralApiException("OSMT user must have an Admin role.", HttpStatus.UNAUTHORIZED)
        }
        val task = CsvTask(collectionUuid = "FullLibrary")
        taskMessageService.enqueueJob(TaskMessageService.skillsForFullLibraryCsv, task)
        
        return Task.processingResponse(task)
    }
    
    @Transactional(readOnly = true)
    @GetMapping(path = [
        "${RoutePaths.API}${RoutePaths.API_V2}${RoutePaths.EXPORT_LIBRARY}",
        "${RoutePaths.API}${RoutePaths.UNVERSIONED}${RoutePaths.EXPORT_LIBRARY}",
    ],
        produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun exportLibraryV2(
        @AuthenticationPrincipal user: Jwt?
    ): HttpEntity<TaskResult> {
        if (!appConfig.allowPublicSearching && user === null) {
            throw GeneralApiException("Unauthorized", HttpStatus.UNAUTHORIZED)
        }
        if (!oAuthHelper.hasRole(appConfig.roleAdmin)) {
            throw GeneralApiException("OSMT user must have an Admin role.", HttpStatus.UNAUTHORIZED)
        }
        val task = CsvTaskV2(collectionUuid = "FullLibrary")
        taskMessageService.enqueueJob(TaskMessageService.skillsForFullLibraryCsv, task)
        
        return Task.processingResponse(task)
    }
    
    @Transactional(readOnly = true)
    @GetMapping("${RoutePaths.API}${RoutePaths.API_V3}${RoutePaths.EXPORT_LIBRARY_XLSX}", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun exportLibraryXlsx(
        @AuthenticationPrincipal user: Jwt?
    ): HttpEntity<TaskResult> {
        if (!appConfig.allowPublicSearching && user === null) {
            throw GeneralApiException("Unauthorized", HttpStatus.UNAUTHORIZED)
        }
        if (!oAuthHelper.hasRole(appConfig.roleAdmin)) {
            throw GeneralApiException("OSMT user must have an Admin role.", HttpStatus.UNAUTHORIZED)
        }
        val task = XlsxTask(collectionUuid = "FullLibrary")
        taskMessageService.enqueueJob(TaskMessageService.skillsForFullLibraryXlsx, task)
        
        return Task.processingResponse(task)
    }
    
    @Transactional(readOnly = true)
    @PostMapping(path = [
        "${RoutePaths.API}${RoutePaths.API_V3}${RoutePaths.EXPORT_SKILLS_CSV}"
    ],
        produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun exportCustomListCsv(
        @RequestBody apiSearch: ApiSearch,
        status: Array<String>,
        @AuthenticationPrincipal user: Jwt?
    ): HttpEntity<TaskResult> {
        if (!appConfig.allowPublicSearching && user === null) {
            throw GeneralApiException("Unauthorized", HttpStatus.UNAUTHORIZED)
        }
        val publishStatuses = status.mapNotNull {
            val status = PublishStatus.forApiValue(it)
            if (user == null && (status == PublishStatus.Deleted || status == PublishStatus.Draft)) null else status
        }.toSet()
        val task = ExportSkillsToCsvTask(
            collectionUuid = "CustomList",
            richSkillEsRepo.getUuidsFromApiSearch(
                apiSearch,
                publishStatuses,
                Pageable.unpaged(),
                user,
                StringUtils.EMPTY
            )
        )
        taskMessageService.enqueueJob(TaskMessageService.skillsForCustomListExportCsv, task)
        
        return Task.processingResponse(task)
    }
    
    @Transactional(readOnly = true)
    @PostMapping(path = [
        "${RoutePaths.API}${RoutePaths.API_V2}${RoutePaths.EXPORT_SKILLS}",
        "${RoutePaths.API}${RoutePaths.UNVERSIONED}${RoutePaths.EXPORT_SKILLS}"
    ],
        produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun exportCustomListCsvV2(
        @RequestBody apiSearch: ApiSearch,
        status: Array<String>,
        @AuthenticationPrincipal user: Jwt?
    ): HttpEntity<TaskResult> {
        if (!appConfig.allowPublicSearching && user === null) {
            throw GeneralApiException("Unauthorized", HttpStatus.UNAUTHORIZED)
        }
        val publishStatuses = status.mapNotNull {
            val status = PublishStatus.forApiValue(it)
            if (user == null && (status == PublishStatus.Deleted || status == PublishStatus.Draft)) null else status
        }.toSet()
        val task = ExportSkillsToCsvTaskV2(
            collectionUuid = "CustomList",
            richSkillEsRepo.getUuidsFromApiSearch(
                apiSearch,
                publishStatuses,
                Pageable.unpaged(),
                user,
                StringUtils.EMPTY
            )
        )
        taskMessageService.enqueueJob(TaskMessageService.skillsForCustomListExportCsvV2, task)
        
        return Task.processingResponse(task)
    }
    
    @Transactional(readOnly = true)
    @PostMapping("${RoutePaths.API}${RoutePaths.API_V3}${RoutePaths.EXPORT_SKILLS_XLSX}", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun exportCustomListXlsx(
        @RequestBody apiSearch: ApiSearch,
        status: Array<String>,
        @AuthenticationPrincipal user: Jwt?
    ): HttpEntity<TaskResult> {
        if (!appConfig.allowPublicSearching && user === null) {
            throw GeneralApiException("Unauthorized", HttpStatus.UNAUTHORIZED)
        }
        val publishStatuses = status.mapNotNull {
            val status = PublishStatus.forApiValue(it)
            if (user == null && (status == PublishStatus.Deleted || status == PublishStatus.Draft)) null else status
        }.toSet()
        val task = ExportSkillsToXlsxTask(collectionUuid = "CustomList", richSkillEsRepo.getUuidsFromApiSearch(apiSearch, publishStatuses, Pageable.unpaged(), user, StringUtils.EMPTY))
        taskMessageService.enqueueJob(TaskMessageService.skillsForCustomListExportXlsx, task)
        
        return Task.processingResponse(task)
    }
}
