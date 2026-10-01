package com.archery.range;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

import com.archery.range.common.BizException;
import com.archery.range.domain.GuardianConsentVersion;
import com.archery.range.domain.MinorVisit;
import com.archery.range.domain.MinorVisitResource;
import com.archery.range.dto.GuardianDtos;
import com.archery.range.dto.GuardianDtos.VersionContent;
import com.archery.range.dto.MemberDtos;
import com.archery.range.repository.GuardianConsentVersionRepository;
import com.archery.range.repository.MemberRepository;
import com.archery.range.repository.MinorVisitRepository;
import com.archery.range.service.GuardianConsentService;
import com.archery.range.service.MemberService;
import com.archery.range.service.MinorVisitService;

/**
 * 未成年会员监护授权台端到端集成测试（H2 MySQL 模式加载真实 schema.sql 种子数据）。
 *
 * 种子：许静(id=10) 授权链1 v2 有效（v1 已被取代）；吴桐(id=6) 授权链2 v1 待监护人确认；
 *      唐宁(id=12) 无授权；许静另有一笔已交还的历史交接 v1。
 *
 * schema 在同一 Spring 上下文内只加载一次，因此各用例自建独立未成年会员，互不干扰。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GuardianFlowIntegrationTest {

    @Autowired
    private GuardianConsentService consentService;
    @Autowired
    private MinorVisitService visitService;
    @Autowired
    private MemberService memberService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private GuardianConsentVersionRepository versionRepository;
    @Autowired
    private MinorVisitRepository visitRepository;

    private static final AtomicInteger SEQ = new AtomicInteger(100);

    private LocalDate today() {
        return LocalDate.now();
    }

    /** 每个用例一个独立未成年会员（唯一卡号 / 手机号） */
    private Long newMinor() {
        int n = SEQ.incrementAndGet();
        MemberDtos.MemberView m = memberService.create(new MemberDtos.MemberSaveReq(
                String.format("AR-TEST-%04d", n), "未成年" + n, String.format("139%08d", n),
                "NORMAL", new BigDecimal("500.00"), true, LocalDate.now().minusYears(12)));
        return m.id();
    }

    private GuardianDtos.PickupPersonReq pickup(String name, String idNo, String relation) {
        return new GuardianDtos.PickupPersonReq(name, idNo, "13900000001", relation);
    }

    private VersionContent content(LocalDate from, LocalDate until,
            List<Long> courses, List<Long> lanes, List<Long> equips,
            List<GuardianDtos.PickupPersonReq> pickups) {
        return new VersionContent(from, until, courses, lanes, equips, pickups, null);
    }

    private GuardianDtos.OperatorReq op(String operator) {
        return new GuardianDtos.OperatorReq(operator, null);
    }

    private GuardianDtos.PersonReq person(String name, String idNo, String relation) {
        return new GuardianDtos.PersonReq(name, idNo, "13900000001", relation);
    }

    private Long buildConfirmedChain(Long memberId) {
        LocalDate today = today();
        Long id = consentService.create(new GuardianDtos.CreateConsentReq(
                memberId, "唐父", "13900000002", "FATHER", "唐父", "13900000002", null,
                content(today, today.plusMonths(3),
                        List.of(8L, 9L), List.of(1L, 3L), List.of(7L, 9L, 11L),
                        List.of(pickup("唐父", "1111", "FATHER"), pickup("唐奶奶", "2222", "GRANDPARENT"))),
                "前台-马倩")).summary().id();
        consentService.submit(id, op("前台-马倩"));
        consentService.confirm(id, new GuardianDtos.ConfirmReq(
                "唐父", "FATHER", 1, null, false, null));
        return id;
    }

    private void assertGuardBlocked(Long memberId, String type, Long resourceId, String expectedWord) {
        assertThatThrownBy(() -> visitService.guard(memberRepository.findById(memberId).orElseThrow(),
                type, resourceId, "测试动作"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(expectedWord);
    }

    // ---------- 1. 种子数据：有效链 / 待确认链 / 历史交接 ----------

    @Test
    @Order(1)
    void seed_consentsAndHistory() {
        GuardianDtos.ConsentView xj = consentService.detail(1L);
        assertThat(xj.summary().status()).isEqualTo("VALID");
        assertThat(xj.summary().currentVersionNo()).isEqualTo(2);
        // 旧版本 v1 已被取代但仍可查
        assertThat(xj.versions()).extracting(GuardianDtos.VersionView::versionNo).containsExactly(2, 1);
        assertThat(xj.versions()).filteredOn(v -> v.versionNo() == 1)
                .singleElement().extracting(GuardianDtos.VersionView::status).isEqualTo("SUPERSEDED");
        // 完整轨迹可还原（建立 / 确认 / 新版 / 取代 / 再确认）
        assertThat(xj.logs()).extracting(GuardianDtos.ConsentLogView::action)
                .contains("CREATE", "CONFIRM", "NEW_VERSION", "SUPERSEDE");

        GuardianDtos.ConsentView wt = consentService.detail(2L);
        assertThat(wt.summary().status()).isEqualTo("PENDING_CONFIRM");

        // 唐宁(12) 无授权：放行判断为空；吴桐(6) 有待确认链但无有效版本
        assertThat(consentService.findEffectiveVersion(12L)).isNull();
        assertThat(consentService.findEffectiveVersion(6L)).isNull();

        // 许静历史交接已交还，不影响开新单
        List<GuardianDtos.VisitSummary> open = visitService.list(true, 10L);
        assertThat(open).isEmpty();
    }

    // ---------- 2. 草稿 → 确认 → 有效；未确认不放行 ----------

    @Test
    @Order(2)
    void draftSubmitConfirm_flow() {
        Long memberId = newMinor();
        LocalDate today = today();
        GuardianDtos.ConsentView created = consentService.create(new GuardianDtos.CreateConsentReq(
                memberId, "唐父", "13900000002", "FATHER", "唐父", "13900000002", null,
                content(today, today.plusMonths(1), List.of(9L), List.of(3L), List.of(9L),
                        List.of(pickup("唐父", "1111", "FATHER"))),
                "前台-马倩"));
        Long chainId = created.summary().id();
        assertThat(created.summary().status()).isEqualTo("DRAFT");
        // 草稿阶段建交接被拦截
        assertThatThrownBy(() -> visitService.create(new GuardianDtos.CreateVisitReq(memberId, "前台-马倩")))
                .isInstanceOf(BizException.class).hasMessageContaining("监护人确认");

        // 草稿可以就地修改
        consentService.updateDraft(chainId, new GuardianDtos.UpdateDraftReq(
                content(today, today.plusMonths(2), List.of(8L, 9L), List.of(3L, 4L),
                        List.of(7L, 9L), List.of(pickup("唐父", "1111", "FATHER"))),
                "前台-马倩"));

        consentService.submit(chainId, op("前台-马倩"));
        // 提交后再想改内容 → 必须发新版本
        assertThatThrownBy(() -> consentService.updateDraft(chainId, new GuardianDtos.UpdateDraftReq(
                content(today, today.plusMonths(2), List.of(9L), List.of(3L), List.of(9L),
                        List.of(pickup("唐父", "1111", "FATHER"))), "前台-马倩")))
                .isInstanceOf(BizException.class).hasMessageContaining("新版本");

        // 监护人拒绝（缺原因先被拦）
        assertThatThrownBy(() -> consentService.confirm(chainId, new GuardianDtos.ConfirmReq(
                "唐父", "FATHER", 1, null, true, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("原因");
        // 正式拒绝：v1 REJECTED、链回草稿
        consentService.confirm(chainId, new GuardianDtos.ConfirmReq(
                "唐父", "FATHER", 1, null, true, "课程时间不合适，需调整"));
        assertThat(consentService.detail(chainId).versions().get(0).status()).isEqualTo("REJECTED");
        // 已拒绝版本关闭，按意见发新版本 v2
        consentService.newVersion(chainId, new GuardianDtos.NewVersionReq(
                content(today, today.plusMonths(1), List.of(8L, 9L), List.of(3L), List.of(9L),
                        List.of(pickup("唐父", "1111", "FATHER"))),
                "前台-马倩"));
        consentService.submit(chainId, op("前台-马倩"));

        // 依据过期版本号确认 → 被拒并提示当前版本
        assertThatThrownBy(() -> consentService.confirm(chainId, new GuardianDtos.ConfirmReq(
                "唐父", "FATHER", 1, null, false, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("v2");

        GuardianDtos.ConsentView confirmed = consentService.confirm(chainId, new GuardianDtos.ConfirmReq(
                "唐父", "FATHER", 2, "证件已核", false, null));
        assertThat(confirmed.summary().status()).isEqualTo("VALID");

        // 重复确认 → 拒绝
        assertThatThrownBy(() -> consentService.confirm(chainId, new GuardianDtos.ConfirmReq(
                "唐父", "FATHER", 2, null, false, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("请勿重复确认");

        // 有效版本可被放行判断取到
        assertThat(consentService.findEffectiveVersion(memberId).getVersionNo()).isEqualTo(2);
    }

    // ---------- 3. 范围整体校验：空范围 / 不存在资源整体失败 ----------

    @Test
    @Order(3)
    void create_scopeMustBeComplete() {
        Long memberId = newMinor();
        LocalDate today = today();
        // 一门课都没选
        assertThatThrownBy(() -> consentService.create(new GuardianDtos.CreateConsentReq(
                memberId, "唐父", "13900000002", "FATHER", "唐父", "13900000002", null,
                content(today, today.plusMonths(1), List.of(), List.of(3L), List.of(9L),
                        List.of(pickup("唐父", "1111", "FATHER"))), "前台-马倩")))
                .isInstanceOf(BizException.class).hasMessageContaining("课程");

        // 不存在的课程 id
        assertThatThrownBy(() -> consentService.create(new GuardianDtos.CreateConsentReq(
                memberId, "唐父", "13900000002", "FATHER", "唐父", "13900000002", null,
                content(today, today.plusMonths(1), List.of(999L), List.of(3L), List.of(9L),
                        List.of(pickup("唐父", "1111", "FATHER"))), "前台-马倩")))
                .isInstanceOf(BizException.class).hasMessageContaining("课程");

        // 无接管人
        assertThatThrownBy(() -> consentService.create(new GuardianDtos.CreateConsentReq(
                memberId, "唐父", "13900000002", "FATHER", "唐父", "13900000002", null,
                content(today, today.plusMonths(1), List.of(9L), List.of(3L), List.of(9L),
                        List.of()), "前台-马倩")))
                .isInstanceOf(BizException.class).hasMessageContaining("接管");

        // 整体失败后没有留下授权链
        assertThat(consentService.list(memberId)).isEmpty();
    }

    // ---------- 4. 新版本：旧 VALID 立即 SUPERSEDED，未确认前不放行 ----------

    @Test
    @Order(4)
    void newVersion_immediatelyStopsOldBaseline() {
        Long memberId = newMinor();
        Long chainId = buildConfirmedChain(memberId);
        GuardianConsentVersion v1 = consentService.findEffectiveVersion(memberId);
        assertThat(v1.getStatus()).isEqualTo("VALID");

        // 缩小范围（去掉课程 8、箭道 1、器材 7/11）发 v2 草稿
        consentService.newVersion(chainId, new GuardianDtos.NewVersionReq(
                content(today(), today().plusMonths(3), List.of(9L), List.of(3L), List.of(9L),
                        List.of(pickup("唐父", "1111", "FATHER"))),
                "前台-马倩"));

        // v1 立即被取代、链转草稿，没有放行窗口
        GuardianConsentVersion old = versionRepository.findById(v1.getId()).orElseThrow();
        assertThat(old.getStatus()).isEqualTo("SUPERSEDED");
        assertThat(consentService.findEffectiveVersion(memberId)).isNull();
        // 建交接被拦
        assertThatThrownBy(() -> visitService.create(new GuardianDtos.CreateVisitReq(memberId, "前台-马倩")))
                .isInstanceOf(BizException.class).hasMessageContaining("确认");

        // 提交 + 确认 v2 后恢复有效
        consentService.submit(chainId, op("前台-马倩"));
        consentService.confirm(chainId, new GuardianDtos.ConfirmReq(
                "唐父", "FATHER", 2, null, false, null));
        assertThat(consentService.findEffectiveVersion(memberId).getVersionNo()).isEqualTo(2);
        // 旧版本仍可查询
        GuardianDtos.ConsentView detail = consentService.detail(chainId);
        assertThat(detail.versions()).hasSize(2);
        assertThat(detail.versions().get(1).status()).isEqualTo("SUPERSEDED");
    }

    // ---------- 5. 到馆交接全流程：接管 → 整组放行 → 交还 ----------

    @Test
    @Order(5)
    void visit_pickupReleaseReturn_happyPath() {
        Long memberId = newMinor();
        buildConfirmedChain(memberId);

        // 建单
        GuardianDtos.VisitView visit = visitService.create(
                new GuardianDtos.CreateVisitReq(memberId, "前台-马倩"));
        Long visitId = visit.summary().id();
        assertThat(visit.summary().status()).isEqualTo("PENDING_PICKUP");
        assertThat(visit.summary().baselineVersionNo()).isEqualTo(1);

        // 未交还前不能开下一次到馆
        assertThatThrownBy(() -> visitService.create(new GuardianDtos.CreateVisitReq(memberId, "前台-马倩")))
                .isInstanceOf(BizException.class).hasMessageContaining("未完成");

        // 接管人不在名单 → 拦截，单仍是待接管（可重试不留半成品）
        assertThatThrownBy(() -> visitService.pickup(visitId, new GuardianDtos.PickupReq(
                "前台-马倩", person("陌生人", "9999", "OTHER"),
                List.of(9L), List.of(3L), List.of(9L))))
                .isInstanceOf(BizException.class).hasMessageContaining("名单");
        assertThat(visitService.detail(visitId).summary().status()).isEqualTo("PENDING_PICKUP");

        // 正确接管人（祖母）接管成功并钉基线、登记资源
        GuardianDtos.VisitView picked = visitService.pickup(visitId, new GuardianDtos.PickupReq(
                "前台-马倩", person("唐奶奶", "2222", "GRANDPARENT"),
                List.of(9L), List.of(3L, 1L), List.of(9L, 11L)));
        assertThat(picked.summary().status()).isEqualTo("TRAINING");
        assertThat(picked.summary().pickupPersonName()).isEqualTo("唐奶奶");
        assertThat(picked.resources()).hasSize(5);
        assertThat(picked.resources()).allSatisfy(r ->
                assertThat(r.status()).isEqualTo("PENDING"));

        // 重复接管拒绝
        assertThatThrownBy(() -> visitService.pickup(visitId, new GuardianDtos.PickupReq(
                "前台-马倩", person("唐奶奶", "2222", "GRANDPARENT"),
                List.of(9L), List.of(3L), List.of(9L))))
                .isInstanceOf(BizException.class).hasMessageContaining("不能重复接管");

        // 整组放行：全部被 v1 覆盖
        GuardianDtos.VisitView released = visitService.release(visitId,
                new GuardianDtos.ReleaseReq("苏禾", null, null, null));
        assertThat(released.resources()).allSatisfy(r ->
                assertThat(r.status()).isEqualTo("RELEASED"));
        assertThat(released.summary().startedAt()).isNotNull();

        // 门禁：未在台账内的器材 7（虽在授权范围内）→ 仍被门禁拦（未放行）
        assertGuardBlocked(memberId, MinorVisitResource.EQUIPMENT, 7L, "放行范围");
        // 授权范围外的箭道 4 → 门禁拦（v1 含 1,3）
        assertGuardBlocked(memberId, MinorVisitResource.LANE, 4L, "范围");
        // 已放行资源通过（无异常）
        visitService.guard(memberRepository.findById(memberId).orElseThrow(),
                MinorVisitResource.LANE, 3L, "开台");
        visitService.guard(memberRepository.findById(memberId).orElseThrow(),
                MinorVisitResource.EQUIPMENT, 9L, "租借");

        // 训练结束 → 交还：名单外接管人被拦，单保持待交还不能静默关闭
        visitService.requestReturn(visitId, op("前台-马倩"));
        assertThatThrownBy(() -> visitService.completeReturn(visitId, new GuardianDtos.ReturnReq(
                "前台-马倩", person("陌生人", "9999", "OTHER"), false, null, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("名单");
        assertThat(visitService.detail(visitId).summary().status()).isEqualTo("PENDING_RETURN");

        // 非名单人 + 经理异常处置：缺经理 / 缺原因均被拦；填齐后放行
        assertThatThrownBy(() -> visitService.completeReturn(visitId, new GuardianDtos.ReturnReq(
                "前台-马倩", person("邻居", "3333", "OTHER"), true, "值班经理-周经理", "")))
                .isInstanceOf(BizException.class).hasMessageContaining("原因");
        assertThatThrownBy(() -> visitService.completeReturn(visitId, new GuardianDtos.ReturnReq(
                "前台-马倩", person("邻居", "3333", "OTHER"), true, "", "电话核实")))
                .isInstanceOf(BizException.class).hasMessageContaining("值班经理");
        GuardianDtos.VisitView returned = visitService.completeReturn(visitId, new GuardianDtos.ReturnReq(
                "前台-马倩", person("邻居", "3333", "OTHER"),
                true, "值班经理-周经理", "父母临时委托邻居，电话核实登记"));
        assertThat(returned.summary().status()).isEqualTo("RETURNED");
        assertThat(returned.summary().managerOverride()).isTrue();
        assertThat(returned.summary().qualifiedPickup()).isFalse();

        // 已交还不能重复确认
        assertThatThrownBy(() -> visitService.completeReturn(visitId, new GuardianDtos.ReturnReq(
                "前台-马倩", person("唐父", "1111", "FATHER"), false, null, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("已完成交还");

        // 闭环后允许开下一次到馆
        GuardianDtos.VisitView next = visitService.create(
                new GuardianDtos.CreateVisitReq(memberId, "前台-马倩"));
        assertThat(next.summary().status()).isEqualTo("PENDING_PICKUP");
        // 清理：取消新单避免污染其他用例
        visitService.cancel(next.summary().id(),
                new GuardianDtos.CancelReq("前台-马倩", "测试清理"));
    }

    // ---------- 6. 整组校验失败不留部分放行；重试不制造资源半成品 ----------

    @Test
    @Order(6)
    void release_allOrNothing_andRetryNoDuplicates() {
        Long memberId = newMinor();
        buildConfirmedChain(memberId);
        Long visitId = visitService.create(new GuardianDtos.CreateVisitReq(memberId, "前台-马倩")).summary().id();
        // 声明包含授权范围外资源：课程 6（v1 课程 8,9）、箭道 4（v1 1,3）、器材 4（v1 7,9,11）
        visitService.pickup(visitId, new GuardianDtos.PickupReq(
                "前台-马倩", person("唐父", "1111", "FATHER"),
                List.of(6L, 9L), List.of(3L, 4L), List.of(4L)));

        try {
            visitService.release(visitId, new GuardianDtos.ReleaseReq("苏禾", null, null, null));
            org.assertj.core.api.Assertions.fail("应整组拦截");
        } catch (BizException e) {
            assertThat(e.getMessage()).contains("整组放行被拦截");
        }
        GuardianDtos.VisitView blocked = visitService.detail(visitId);
        // 没有任何资源被放行
        assertThat(blocked.resources()).allSatisfy(r ->
                assertThat(r.status()).isEqualTo("PENDING"));
        long blockLogs = blocked.logs().stream().filter(l -> l.action().equals("BLOCK")).count();

        // 再试一次：仍然全 PENDING（资源台账无半成品 / 无重复行）；拦截轨迹如实追加
        assertThatThrownBy(() -> visitService.release(visitId,
                new GuardianDtos.ReleaseReq("苏禾", null, null, null)))
                .isInstanceOf(BizException.class);
        GuardianDtos.VisitView blocked2 = visitService.detail(visitId);
        assertThat(blocked2.resources()).hasSize(blocked.resources().size());
        assertThat(blocked2.resources()).allSatisfy(r ->
                assertThat(r.status()).isEqualTo("PENDING"));
        assertThat(blocked2.logs().stream().filter(l -> l.action().equals("BLOCK")).count())
                .isEqualTo(blockLogs + 1);

        // 工作人员移除授权外资源后整组校验通过（演示半完成交接如何安全收敛，不留死单）
        visitService.trimResources(visitId, new GuardianDtos.ReleaseReq(
                "前台-马倩", List.of(6L), List.of(4L), List.of(4L)));
        visitService.release(visitId, new GuardianDtos.ReleaseReq("苏禾", null, null, null));
        visitService.requestReturn(visitId, op("前台-马倩"));
        visitService.completeReturn(visitId, new GuardianDtos.ReturnReq(
                "前台-马倩", person("唐父", "1111", "FATHER"), false, null, null));
    }

    // ---------- 7. 接管后基线变化：冻结未开始动作，事实不改写，recheck 后继续 ----------

    @Test
    @Order(7)
    void baselineDrift_freezesUnstartedActions_thenRecheck() {
        Long memberId = newMinor();
        Long chainId = buildConfirmedChain(memberId);

        Long visitId = visitService.create(new GuardianDtos.CreateVisitReq(memberId, "前台-马倩")).summary().id();
        visitService.pickup(visitId, new GuardianDtos.PickupReq(
                "前台-马倩", person("唐父", "1111", "FATHER"),
                List.of(8L, 9L), List.of(1L, 3L), List.of(7L, 9L)));
        // 先放行一部分：课程 9 + 箭道 3 + 器材 9
        visitService.release(visitId, new GuardianDtos.ReleaseReq(
                "苏禾", List.of(9L), List.of(3L), List.of(9L)));

        // 监护人缩小范围发 v2（去掉课程 8、箭道 1、器材 7）并确认
        consentService.newVersion(chainId, new GuardianDtos.NewVersionReq(
                content(today(), today().plusMonths(3), List.of(9L), List.of(3L), List.of(9L),
                        List.of(pickup("唐父", "1111", "FATHER"))),
                "前台-马倩"));
        consentService.submit(chainId, op("前台-马倩"));
        consentService.confirm(chainId, new GuardianDtos.ConfirmReq(
                "唐父", "FATHER", 2, null, false, null));

        // 交接单冻结：未放行 PENDING 项 → FROZEN；已放行 RELEASED 事实保留；基线仍钉 v1
        GuardianDtos.VisitView frozen = visitService.detail(visitId);
        assertThat(frozen.summary().baselineFrozen()).isTrue();
        assertThat(frozen.summary().baselineVersionNo()).isEqualTo(1);
        assertThat(frozen.resources()).filteredOn(r -> r.status().equals("RELEASED")).hasSize(3);
        assertThat(frozen.resources()).filteredOn(r -> r.status().equals("FROZEN")).hasSize(3);
        assertThat(frozen.logs()).anyMatch(l -> l.action().equals("FREEZE"));

        // 冻结期间门禁拦截（基线漂移，即使资源曾放行）
        assertGuardBlocked(memberId, MinorVisitResource.LANE, 3L, "冻结");

        // 直接放行被拦
        assertThatThrownBy(() -> visitService.release(visitId,
                new GuardianDtos.ReleaseReq("苏禾", null, null, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("冻结");

        // recheck：新基线 v2 不覆盖已冻结的课程8/箭道1/器材7 → 整体不通过
        assertThatThrownBy(() -> visitService.recheck(visitId,
                new GuardianDtos.RecheckReq("前台-马倩")))
                .isInstanceOf(BizException.class).hasMessageContaining("重新核对未通过");

        // 工作人员移除新基线下不覆盖的未放行资源（已放行事实不动）
        visitService.trimResources(visitId, new GuardianDtos.ReleaseReq(
                "前台-马倩", List.of(8L), List.of(1L), List.of(7L)));
        // 再 recheck 通过：基线切到 v2
        GuardianDtos.VisitView rebound = visitService.recheck(visitId,
                new GuardianDtos.RecheckReq("前台-马倩"));
        assertThat(rebound.summary().baselineFrozen()).isFalse();
        assertThat(rebound.summary().baselineVersionNo()).isEqualTo(2);
        assertThat(rebound.resources()).allSatisfy(r ->
                assertThat(r.status()).isIn("RELEASED", "PENDING"));
        // 接管事实保留
        assertThat(rebound.summary().pickupPersonName()).isEqualTo("唐父");

        // 重新核对后门禁恢复
        visitService.guard(memberRepository.findById(memberId).orElseThrow(),
                MinorVisitResource.LANE, 3L, "开台");
        // 轨迹可还原：FREEZE / EXCEPTION(移除) / RECHECK
        assertThat(rebound.logs()).extracting(GuardianDtos.VisitLogView::action)
                .contains("FREEZE", "EXCEPTION", "RECHECK");

        // 安全交还闭环
        visitService.requestReturn(visitId, op("前台-马倩"));
        visitService.completeReturn(visitId, new GuardianDtos.ReturnReq(
                "前台-马倩", person("唐父", "1111", "FATHER"), false, null, null));
    }

    // ---------- 8. 暂停 / 撤销立即冻结在途交接，已开始训练安全交还 ----------

    @Test
    @Order(8)
    void suspendAndRevoke_freezeOpenVisit() {
        Long memberId = newMinor();
        Long chainId = buildConfirmedChain(memberId);
        Long visitId = visitService.create(new GuardianDtos.CreateVisitReq(memberId, "前台-马倩")).summary().id();
        visitService.pickup(visitId, new GuardianDtos.PickupReq(
                "前台-马倩", person("唐父", "1111", "FATHER"),
                List.of(9L), List.of(3L), List.of(9L)));

        // 暂停
        consentService.suspend(chainId, new GuardianDtos.OperatorReq("值班经理-周经理", "监护人电话暂时无法接通"));
        GuardianDtos.VisitView frozen = visitService.detail(visitId);
        assertThat(frozen.summary().baselineFrozen()).isTrue();
        assertGuardBlocked(memberId, MinorVisitResource.LANE, 3L, "暂停");
        // 已接管训练仍可走异常处置安全交还
        visitService.requestReturn(visitId, op("前台-马倩"));
        visitService.completeReturn(visitId, new GuardianDtos.ReturnReq(
                "前台-马倩", person("唐父", "1111", "FATHER"), false, null, null));

        // 恢复后再开一单并撤销：撤销同样冻结，已开始训练留轨迹至交还
        consentService.resume(chainId, op("值班经理-周经理"));
        Long visitId2 = visitService.create(new GuardianDtos.CreateVisitReq(memberId, "前台-马倩")).summary().id();
        visitService.pickup(visitId2, new GuardianDtos.PickupReq(
                "前台-马倩", person("唐父", "1111", "FATHER"),
                List.of(9L), List.of(3L), List.of(9L)));
        visitService.release(visitId2, new GuardianDtos.ReleaseReq("苏禾", null, null, null));
        consentService.revoke(chainId, new GuardianDtos.OperatorReq("值班经理-周经理", "监护人书面要求撤销"));
        GuardianDtos.VisitView frozen2 = visitService.detail(visitId2);
        assertThat(frozen2.summary().baselineFrozen()).isTrue();
        assertThat(frozen2.resources()).filteredOn(r -> r.status().equals("RELEASED")).isNotEmpty();
        // 撤销不能重复
        assertThatThrownBy(() -> consentService.revoke(chainId,
                new GuardianDtos.OperatorReq("值班经理-周经理", "再次撤销")))
                .isInstanceOf(BizException.class).hasMessageContaining("已撤销");
        // 撤销链不能恢复，只能发新版本
        assertThatThrownBy(() -> consentService.resume(chainId, op("值班经理-周经理")))
                .isInstanceOf(BizException.class);
        // 已开始训练安全交还闭环
        visitService.requestReturn(visitId2, op("前台-马倩"));
        visitService.completeReturn(visitId2, new GuardianDtos.ReturnReq(
                "前台-马倩", person("唐父", "1111", "FATHER"), false, null, null));
    }

    // ---------- 9. 并发：两人同时确认 / 同时接管，只一个结果生效 ----------

    @Test
    @Order(9)
    void concurrentConfirmAndPickup_onlyOneWins() throws Exception {
        Long memberId = newMinor();
        LocalDate today = today();
        Long chainId = consentService.create(new GuardianDtos.CreateConsentReq(
                memberId, "唐父", "13900000002", "FATHER", "唐父", "13900000002", null,
                content(today, today.plusMonths(1), List.of(9L), List.of(3L), List.of(9L),
                        List.of(pickup("唐父", "1111", "FATHER"))),
                "前台-马倩")).summary().id();
        consentService.submit(chainId, op("前台-马倩"));

        int threads = 2;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicReference<Throwable> loser = new AtomicReference<>();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    consentService.confirm(chainId, new GuardianDtos.ConfirmReq(
                            "唐父", "FATHER", 1, null, false, null));
                } catch (Throwable t) {
                    loser.set(t);
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        // 只允许一个确认结果生效：败者得到明确冲突反馈，链唯一成为有效
        assertThat(loser.get()).isNotNull();
        assertThat(consentService.detail(chainId).summary().status()).isEqualTo("VALID");

        // 并发接管：只有一个成功
        Long visitId = visitService.create(new GuardianDtos.CreateVisitReq(memberId, "前台-马倩")).summary().id();
        CountDownLatch start2 = new CountDownLatch(1);
        ExecutorService pool2 = Executors.newFixedThreadPool(threads);
        AtomicReference<Throwable> loser2 = new AtomicReference<>();
        List<Future<?>> futures2 = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures2.add(pool2.submit(() -> {
                try {
                    start2.await();
                    visitService.pickup(visitId, new GuardianDtos.PickupReq(
                            "前台-马倩", person("唐父", "1111", "FATHER"),
                            List.of(9L), List.of(3L), List.of(9L)));
                } catch (Throwable t) {
                    loser2.set(t);
                }
            }));
        }
        start2.countDown();
        for (Future<?> f : futures2) {
            f.get();
        }
        pool2.shutdown();
        // 只允许一个接管结果生效：败者明确失败（重复接管 / 锁冲突），单据唯一进入训练中
        assertThat(loser2.get()).isNotNull();
        MinorVisit visit = visitRepository.findById(visitId).orElseThrow();
        assertThat(visit.getStatus()).isEqualTo("TRAINING");
        assertThat(visit.getPickupAt()).isNotNull();

        // 清理交还
        visitService.requestReturn(visitId, op("前台-马倩"));
        visitService.completeReturn(visitId, new GuardianDtos.ReturnReq(
                "前台-马倩", person("唐父", "1111", "FATHER"), false, null, null));
    }

    // ---------- 10. 成年会员流程不受影响 ----------

    @Test
    @Order(10)
    void adultMember_notGated() {
        // 成年会员 1（陈子昂）：guard 不被监护模块拦截（直接返回）
        assertThat(memberRepository.findById(1L).orElseThrow().isMinorFlag()).isFalse();
        visitService.guard(memberRepository.findById(1L).orElseThrow(),
                MinorVisitResource.LANE, 1L, "开台");
        visitService.guard(memberRepository.findById(1L).orElseThrow(),
                MinorVisitResource.EQUIPMENT, 1L, "租借");
    }

    // ---------- 11. 取消仅待接管；已接管不能取消 ----------

    @Test
    @Order(11)
    void cancel_onlyBeforePickup() {
        Long memberId = newMinor();
        buildConfirmedChain(memberId);
        Long visitId = visitService.create(new GuardianDtos.CreateVisitReq(memberId, "前台-马倩")).summary().id();
        // 原因必填
        assertThatThrownBy(() -> visitService.cancel(visitId, new GuardianDtos.CancelReq("前台-马倩", "")))
                .isInstanceOf(BizException.class).hasMessageContaining("原因");
        visitService.cancel(visitId, new GuardianDtos.CancelReq("前台-马倩", "会员未到场"));
        // 取消后可开新单
        Long again = visitService.create(new GuardianDtos.CreateVisitReq(memberId, "前台-马倩")).summary().id();
        visitService.pickup(again, new GuardianDtos.PickupReq(
                "前台-马倩", person("唐父", "1111", "FATHER"),
                List.of(9L), List.of(3L), List.of(9L)));
        // 已接管不能取消
        assertThatThrownBy(() -> visitService.cancel(again, new GuardianDtos.CancelReq("前台-马倩", "x")))
                .isInstanceOf(BizException.class).hasMessageContaining("安全交还");
        // 清理
        visitService.requestReturn(again, op("前台-马倩"));
        visitService.completeReturn(again, new GuardianDtos.ReturnReq(
                "前台-马倩", person("唐父", "1111", "FATHER"), false, null, null));
    }
}
