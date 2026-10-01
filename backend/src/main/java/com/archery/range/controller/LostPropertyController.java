package com.archery.range.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.archery.range.common.ApiResponse;
import com.archery.range.dto.LostDtos;
import com.archery.range.service.LostPropertyService;

import jakarta.validation.Valid;

/**
 * 模块十二：失物保管与认领交接台。
 *
 * 视图与动作按角色拆分（角色由前端当前岗位传入，服务端对每个动作做岗位强校验）：
 * - 公示视图 /api/lost/public：任何访客，只给模糊类别 / 发现日期 / 馆内区域；
 * - 失物登记与保管：前台登记 + 首次封存，前台 / 保管员查看开封、重封存、转移；
 * - 认领：前台录入会员 / 访客申报与补证，值班经理给出三项分项结论与通过 / 补证 / 驳回；
 * - 现场交还：当前保管人 + 另一名工作人员双人确认；异常挂起由值班经理处置后仍须双人交还；
 * - 逾期处置：值班经理登记返还公安 / 捐赠 / 报废及接收方凭证。
 */
@RestController
@RequestMapping("/api/lost")
public class LostPropertyController {

    private final LostPropertyService service;

    public LostPropertyController(LostPropertyService service) {
        this.service = service;
    }

    /** 表单选项：类别 / 箭道 / 课程 / 存放区域 / 证件方式 / 保管期限 */
    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> options() {
        return ApiResponse.success(service.options());
    }

    // ---------------- 认领人公示（无角色门槛，字段最小化） ----------------

    /** 对认领人公示：模糊类别 + 发现日期 + 馆内区域 */
    @GetMapping("/public")
    public ApiResponse<List<LostDtos.PublicItemView>> publicList() {
        return ApiResponse.success(service.publicList());
    }

    // ---------------- 工作人员台账与保管链 ----------------

    /** 失物台账（scope: all 全部 / open 未闭环 / custody 保管中 / return 待交还异常 / overdue 待处置） */
    @GetMapping("/items")
    public ApiResponse<List<LostDtos.ItemRow>> list(
            @RequestParam(defaultValue = "FRONT") String role,
            @RequestParam(required = false) String scope) {
        return ApiResponse.success(service.list(role, scope));
    }

    /** 失物详情：登记事实 + 封签序列 + 认领（脱敏）+ 交接 + 处置 + 完整交接链 + 当前角色可执行动作 */
    @GetMapping("/items/{id}")
    public ApiResponse<LostDtos.ItemView> detail(@PathVariable Long id,
            @RequestParam(defaultValue = "FRONT") String role) {
        return ApiResponse.success(service.detail(id, role));
    }

    /** 发现人登记失物（类别 / 位置 / 时间 / 外观 / 照片），登记后为待首次封存 */
    @PostMapping("/items")
    public ApiResponse<LostDtos.ItemView> register(@Valid @RequestBody LostDtos.RegisterItemReq req) {
        return ApiResponse.success("失物已登记，请由值班前台生成封签完成首次交接", service.register(req));
    }

    /** 值班前台生成封签并完成首次交接 */
    @PostMapping("/items/{id}/first-seal")
    public ApiResponse<LostDtos.ItemView> firstSeal(@PathVariable Long id,
            @Valid @RequestBody LostDtos.FirstSealReq req) {
        return ApiResponse.success("封签已生成，首次保管交接完成", service.firstSeal(id, req));
    }

    /** 工作人员查看（开封登记原因；查看后须重封存） */
    @PostMapping("/items/{id}/inspect")
    public ApiResponse<LostDtos.ItemView> inspect(@PathVariable Long id,
            @Valid @RequestBody LostDtos.InspectReq req) {
        return ApiResponse.success("已登记开封查看，查看后请立即重新封存", service.inspect(id, req));
    }

    /** 查看 / 核验后重新封存（另发新签） */
    @PostMapping("/items/{id}/reseal")
    public ApiResponse<LostDtos.ItemView> reseal(@PathVariable Long id,
            @Valid @RequestBody LostDtos.ResealReq req) {
        return ApiResponse.success("已重新封存并生成新封签", service.reseal(id, req));
    }

    /** 保管转移：开封旧签 → 核验 → 新保管人重封存，沿用同一条交接链 */
    @PostMapping("/items/{id}/transfer")
    public ApiResponse<LostDtos.ItemView> transfer(@PathVariable Long id,
            @Valid @RequestBody LostDtos.TransferReq req) {
        return ApiResponse.success("保管已转移并重新封存", service.transfer(id, req));
    }

    // ---------------- 认领审核 ----------------

    /** 值班经理审核包（完成核验所需的最少信息；照片默认不含） */
    @GetMapping("/items/{id}/review-pack")
    public ApiResponse<LostDtos.ItemView> reviewPack(@PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean includePhoto) {
        return ApiResponse.success(service.reviewPack(id, includePhoto));
    }

    /** 前台录入认领申报（会员或未建档访客） */
    @PostMapping("/items/{id}/claims")
    public ApiResponse<LostDtos.ItemView> createClaim(@PathVariable Long id,
            @Valid @RequestBody LostDtos.CreateClaimReq req) {
        return ApiResponse.success("认领申报已录入，等待值班经理审核", service.createClaim(id, req));
    }

    /** 认领人按经理要求补证（追加，重新进入待审核） */
    @PostMapping("/claims/{claimId}/supplement")
    public ApiResponse<LostDtos.ItemView> supplement(@PathVariable Long claimId,
            @Valid @RequestBody LostDtos.SupplementReq req) {
        return ApiResponse.success("补充证明已录入，重新提交值班经理审核", service.supplement(claimId, req));
    }

    /** 值班经理审核：特征匹配 / 时间地点吻合 / 补充证明三结论 + 通过 / 补证 / 驳回 */
    @PostMapping("/claims/{claimId}/review")
    public ApiResponse<LostDtos.ItemView> review(@PathVariable Long claimId,
            @Valid @RequestBody LostDtos.ReviewReq req) {
        return ApiResponse.success("审核结论已记录", service.review(claimId, req));
    }

    // ---------------- 现场交还（双人）与异常 ----------------

    /** 审核通过后建立现场交接（待双人确认） */
    @PostMapping("/items/{id}/handovers")
    public ApiResponse<LostDtos.ItemView> startHandover(@PathVariable Long id,
            @RequestParam(defaultValue = "前台-马倩") String operator) {
        return ApiResponse.success("已进入现场交接，等待双人确认", service.startHandover(id, operator));
    }

    /** 当前保管人与另一名工作人员共同完成现场认领交接 */
    @PostMapping("/items/{id}/handovers/complete")
    public ApiResponse<LostDtos.ItemView> completeHandover(@PathVariable Long id,
            @Valid @RequestBody LostDtos.HandoverCompleteReq req) {
        return ApiResponse.success("双人现场交还已完成，认领闭环", service.completeHandover(id, req));
    }

    /** 现场异常上报（封签破损 / 实物不符 / 领取人变更 / 保管人缺席）：暂停交还，保留原审核 */
    @PostMapping("/items/{id}/handovers/exception")
    public ApiResponse<LostDtos.ItemView> reportException(@PathVariable Long id,
            @Valid @RequestBody LostDtos.HandoverExceptionReq req) {
        return ApiResponse.success("已转入异常交接，等待值班经理处置", service.reportException(id, req));
    }

    /** 值班经理异常处置：重新封存 / 补充核验 / 终止本次认领 */
    @PostMapping("/items/{id}/handovers/resolve")
    public ApiResponse<LostDtos.ItemView> resolveException(@PathVariable Long id,
            @Valid @RequestBody LostDtos.ResolveExceptionReq req) {
        return ApiResponse.success("异常处置已记录", service.resolveException(id, req));
    }

    // ---------------- 逾期处置 ----------------

    /** 值班经理批量扫描逾期无人认领物品，转入待处置清单（返回转单数） */
    @PostMapping("/overdue/mark")
    public ApiResponse<Integer> markOverdue(@Valid @RequestBody LostDtos.MarkOverdueReq req) {
        int count = service.markOverdue(req);
        return ApiResponse.success("已将 " + count + " 件逾期物品转入待处置清单", count);
    }

    /** 值班经理登记处置结果（返还公安 / 捐赠 / 报废）与接收方凭证 */
    @PostMapping("/items/{id}/dispose")
    public ApiResponse<LostDtos.ItemView> dispose(@PathVariable Long id,
            @Valid @RequestBody LostDtos.DisposeReq req) {
        return ApiResponse.success("处置结果与接收方凭证已登记", service.dispose(id, req));
    }
}
