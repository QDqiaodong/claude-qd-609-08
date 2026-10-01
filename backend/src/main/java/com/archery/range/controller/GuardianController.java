package com.archery.range.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.archery.range.common.ApiResponse;
import com.archery.range.dto.GuardianDtos;
import com.archery.range.service.GuardianConsentService;
import com.archery.range.service.MinorVisitService;

import jakarta.validation.Valid;

/**
 * 模块十一：未成年会员监护授权台。
 *
 * 授权侧（/api/guardian/consents）：监护授权链 + 不可变版本，草稿 / 待确认 / 有效 / 暂停 / 撤销全程留痕；
 * 交接侧（/api/guardian/visits）：每次到馆一单，待接管 / 训练中 / 待交还 / 已交还四阶段。
 * 前台、教练、器材管理员基于同一份可追溯授权判断未成年会员能否进入课程、箭道与器材流程。
 */
@RestController
@RequestMapping("/api/guardian")
public class GuardianController {

    private final GuardianConsentService consentService;
    private final MinorVisitService visitService;

    public GuardianController(GuardianConsentService consentService, MinorVisitService visitService) {
        this.consentService = consentService;
        this.visitService = visitService;
    }

    /** 表单选项：关系 / 课程 / 箭道 / 器材 / 未成年会员 */
    @GetMapping("/options")
    public ApiResponse<java.util.Map<String, Object>> options() {
        return ApiResponse.success(consentService.options());
    }

    // ---------------- 监护授权链 ----------------

    /** 授权链列表（可按未成年会员过滤） */
    @GetMapping("/consents")
    public ApiResponse<List<GuardianDtos.ConsentSummary>> listConsents(
            @RequestParam(required = false) Long memberId) {
        return ApiResponse.success(consentService.list(memberId));
    }

    /** 授权链详情：全部版本（含已被取代 / 撤销的旧版本）+ 完整轨迹 */
    @GetMapping("/consents/{id}")
    public ApiResponse<GuardianDtos.ConsentView> consentDetail(@PathVariable Long id) {
        return ApiResponse.success(consentService.detail(id));
    }

    /** 建立授权链（前台）：未成年会员 + 监护人 + 紧急联系人 + 首版草稿，范围整组校验 */
    @PostMapping("/consents")
    public ApiResponse<GuardianDtos.ConsentView> createConsent(
            @Valid @RequestBody GuardianDtos.CreateConsentReq req) {
        return ApiResponse.success("监护授权链已建立（草稿），请提交监护人现场确认", consentService.create(req));
    }

    /** 修改草稿内容（仅草稿可改；提交后变化必须发新版本） */
    @PostMapping("/consents/{id}/draft")
    public ApiResponse<GuardianDtos.ConsentView> updateDraft(@PathVariable Long id,
            @Valid @RequestBody GuardianDtos.UpdateDraftReq req) {
        return ApiResponse.success("草稿已更新", consentService.updateDraft(id, req));
    }

    /** 提交监护人确认：草稿 → 待监护人确认 */
    @PostMapping("/consents/{id}/submit")
    public ApiResponse<GuardianDtos.ConsentView> submit(@PathVariable Long id,
            @Valid @RequestBody GuardianDtos.OperatorReq req) {
        return ApiResponse.success("已提交监护人现场确认", consentService.submit(id, req));
    }

    /** 监护人确认 / 拒绝（确认是版本生效唯一入口；重复或过期确认会被拒绝并提示所依据版本） */
    @PostMapping("/consents/{id}/confirm")
    public ApiResponse<GuardianDtos.ConsentView> confirm(@PathVariable Long id,
            @Valid @RequestBody GuardianDtos.ConfirmReq req) {
        boolean reject = Boolean.TRUE.equals(req.reject());
        return ApiResponse.success(reject ? "监护人已拒绝确认，请按意见发布新版本" : "监护人已确认，授权生效",
                consentService.confirm(id, req));
    }

    /** 撤回待确认版本：回到草稿 */
    @PostMapping("/consents/{id}/recall")
    public ApiResponse<GuardianDtos.ConsentView> recall(@PathVariable Long id,
            @Valid @RequestBody GuardianDtos.OperatorReq req) {
        return ApiResponse.success("已撤回到草稿", consentService.recall(id, req));
    }

    /** 发布新版本草稿：范围 / 生效日终止日 / 接管人变化的唯一入口；旧版本立即停止放行 */
    @PostMapping("/consents/{id}/versions")
    public ApiResponse<GuardianDtos.ConsentView> newVersion(@PathVariable Long id,
            @Valid @RequestBody GuardianDtos.NewVersionReq req) {
        return ApiResponse.success("新版本草稿已建立（旧版本停止放行），请提交监护人重新确认",
                consentService.newVersion(id, req));
    }

    /** 暂停有效授权（值班经理，必填原因） */
    @PostMapping("/consents/{id}/suspend")
    public ApiResponse<GuardianDtos.ConsentView> suspend(@PathVariable Long id,
            @Valid @RequestBody GuardianDtos.OperatorReq req) {
        return ApiResponse.success("授权已暂停，在途交接的未开始动作已冻结", consentService.suspend(id, req));
    }

    /** 恢复暂停授权（值班经理）；在途交接仍须显式重新核对 */
    @PostMapping("/consents/{id}/resume")
    public ApiResponse<GuardianDtos.ConsentView> resume(@PathVariable Long id,
            @Valid @RequestBody GuardianDtos.OperatorReq req) {
        return ApiResponse.success("授权已恢复；在途交接须按基线重新核对后才能继续放行",
                consentService.resume(id, req));
    }

    /** 撤销授权（值班经理，终态，必填原因） */
    @PostMapping("/consents/{id}/revoke")
    public ApiResponse<GuardianDtos.ConsentView> revoke(@PathVariable Long id,
            @Valid @RequestBody GuardianDtos.OperatorReq req) {
        return ApiResponse.success("授权已撤销，立即停止一切放行；在途训练须安全交还",
                consentService.revoke(id, req));
    }

    // ---------------- 到馆现场交接 ----------------

    /** 交接单列表：open=true 只看待接管 / 训练中 / 待交还 */
    @GetMapping("/visits")
    public ApiResponse<List<GuardianDtos.VisitSummary>> listVisits(
            @RequestParam(required = false, defaultValue = "false") boolean open,
            @RequestParam(required = false) Long memberId) {
        return ApiResponse.success(visitService.list(open, memberId));
    }

    /** 交接详情：基线版本快照 + 资源放行台账 + 完整轨迹 */
    @GetMapping("/visits/{id}")
    public ApiResponse<GuardianDtos.VisitView> visitDetail(@PathVariable Long id) {
        return ApiResponse.success(visitService.detail(id));
    }

    /** 建立到馆交接（前台）：无当前有效授权直接拦截；上一次未交还不能开新单 */
    @PostMapping("/visits")
    public ApiResponse<GuardianDtos.VisitView> createVisit(
            @Valid @RequestBody GuardianDtos.CreateVisitReq req) {
        return ApiResponse.success("到馆交接单已建立，等待接管人到场", visitService.create(req));
    }

    /** 现场接管（前台）：核对当前有效授权 + 实际接管人身份与名单，钉住基线并登记拟用资源 */
    @PostMapping("/visits/{id}/pickup")
    public ApiResponse<GuardianDtos.VisitView> pickup(@PathVariable Long id,
            @Valid @RequestBody GuardianDtos.PickupReq req) {
        return ApiResponse.success("接管完成，授权基线已钉住；训练开始前须整组校验放行",
                visitService.pickup(id, req));
    }

    /** 训练开始前整组放行（教练）：课程 / 箭道 / 器材任一不覆盖则整组拒绝 */
    @PostMapping("/visits/{id}/release")
    public ApiResponse<GuardianDtos.VisitView> release(@PathVariable Long id,
            @Valid @RequestBody GuardianDtos.ReleaseReq req) {
        return ApiResponse.success("整组校验通过，训练资源已放行", visitService.release(id, req));
    }

    /** 基线冻结后按新基线重新核对（工作人员） */
    @PostMapping("/visits/{id}/recheck")
    public ApiResponse<GuardianDtos.VisitView> recheck(@PathVariable Long id,
            @Valid @RequestBody GuardianDtos.RecheckReq req) {
        return ApiResponse.success("已按当前授权基线重新核对", visitService.recheck(id, req));
    }

    /** 移除新基线下不再覆盖、尚未放行的资源（已放行事实保留） */
    @PostMapping("/visits/{id}/trim-resources")
    public ApiResponse<GuardianDtos.VisitView> trimResources(@PathVariable Long id,
            @Valid @RequestBody GuardianDtos.ReleaseReq req) {
        return ApiResponse.success("未放行资源已移除，可重新整组核对", visitService.trimResources(id, req));
    }

    /** 结束训练，进入待交还（前台） */
    @PostMapping("/visits/{id}/request-return")
    public ApiResponse<GuardianDtos.VisitView> requestReturn(@PathVariable Long id,
            @Valid @RequestBody GuardianDtos.OperatorReq req) {
        return ApiResponse.success("已进入待交还，等待合格接管人", visitService.requestReturn(id, req));
    }

    /** 安全交还（前台）：必须由合格接管人完成；非名单人员须值班经理异常处置 */
    @PostMapping("/visits/{id}/return")
    public ApiResponse<GuardianDtos.VisitView> completeReturn(@PathVariable Long id,
            @Valid @RequestBody GuardianDtos.ReturnReq req) {
        return ApiResponse.success("安全交还已完成，交接闭环", visitService.completeReturn(id, req));
    }

    /** 取消交接（仅待接管，必填原因） */
    @PostMapping("/visits/{id}/cancel")
    public ApiResponse<GuardianDtos.VisitView> cancel(@PathVariable Long id,
            @Valid @RequestBody GuardianDtos.CancelReq req) {
        return ApiResponse.success("交接单已取消", visitService.cancel(id, req));
    }
}
