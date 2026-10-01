package com.archery.range;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

import com.archery.range.common.BizException;
import com.archery.range.domain.LostItem;
import com.archery.range.dto.LostDtos;
import com.archery.range.repository.LostHandoverRepository;
import com.archery.range.repository.LostItemRepository;
import com.archery.range.service.LostPropertyService;

/**
 * 失物保管与认领交接台端到端集成测试（H2 MySQL 模式加载真实 schema.sql 种子数据）。
 *
 * 各用例全部自建失物（与种子物品、其他用例隔离）；需要逾期时直接把自建物品的保管期限拨到过去，
 * 再走真实的逾期扫描 / 处置接口。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
class LostPropertyFlowIntegrationTest {

    @Autowired
    private LostPropertyService service;
    @Autowired
    private LostItemRepository itemRepository;
    @Autowired
    private LostHandoverRepository handoverRepository;

    private LostDtos.RegisterItemReq registerReq(String category, String area,
            boolean highValue, boolean identity) {
        return new LostDtos.RegisterItemReq(
                category, "PUBLIC", null, null, area,
                LocalDateTime.now().minusHours(2), "黑色物品，内侧有白色刻字 ZX，边角磨损",
                "photo://lost/test-1.jpg", highValue, identity,
                "测试发现人", "13800010000", "STAFF", "前台-马倩");
    }

    private LostDtos.ItemView registerAndSeal(String category, String area, boolean highValue, boolean identity) {
        LostDtos.ItemView item = service.register(registerReq(category, area, highValue, identity));
        return service.firstSeal(item.id(), new LostDtos.FirstSealReq(
                "前台-马倩", "FRONT", highValue ? "SAFE" : "FRONT_DESK", "前台-马倩"));
    }

    private LostDtos.CreateClaimReq claimReq(String type, Long memberId, String name, String desc,
            String privacy) {
        return new LostDtos.CreateClaimReq(
                type, memberId, name, "13700000099", desc,
                LocalDateTime.now().minusHours(4), LocalDateTime.now().minusHours(1),
                null, null, "测试公共区域", privacy, "前台-马倩");
    }

    private LostDtos.ReviewReq reviewReq(String feature, String timeplace, String support, String decision) {
        return new LostDtos.ReviewReq(feature, timeplace, support,
                "特征说明", "时地说明", "证明说明", decision,
                "APPROVE".equals(decision) ? "三项核验通过" : "需要进一步处理", false, "值班经理-冯珊");
    }

    private LostDtos.HandoverCompleteReq handoverReq(String custodian, String custodianRole,
            String witness, String witnessRole, String receiver) {
        return new LostDtos.HandoverCompleteReq(
                custodian, custodianRole, witness, witnessRole,
                new LostDtos.ReceiverReq(receiver, "ID_CARD", "5678"), "前台-马倩");
    }

    /** 把自建物品的保管期限拨到过去（测试专用，真实业务只能自然到期） */
    private void expire(Long itemId) {
        LostItem item = itemRepository.findById(itemId).orElseThrow();
        item.setCustodyDeadline(LocalDateTime.now().minusDays(1));
        itemRepository.save(item);
    }

    // ---------- 1. 登记 / 首次封存 / 公示脱敏 ----------

    @Test
    void register_firstSeal_publicIsMasked() {
        LostDtos.ItemView view = registerAndSeal("ELECTRONIC", "测试公共区域", false, false);
        assertThat(view.status()).isEqualTo("IN_CUSTODY");
        assertThat(view.seals()).hasSize(1);
        assertThat(view.seals().get(0).sealState()).isEqualTo("SEALED");

        // 公示只出现模糊类别、发现日期、馆内区域；没有外观 / 照片 / 具体细节
        LostDtos.PublicItemView pub = service.publicList().stream()
                .filter(p -> p.id().equals(view.id())).findFirst().orElseThrow();
        assertThat(pub.publicCategoryName()).isEqualTo("普通物品");
        assertThat(pub.foundDate()).hasSize(10);
        assertThat(pub.areaName()).isEqualTo("测试公共区域");
    }

    @Test
    void coach_cannotSeeEvidenceOrPhotos() {
        LostDtos.ItemView v = registerAndSeal("WALLET", "更衣室", false, true);
        service.createClaim(v.id(), claimReq("VISITOR", null, "访客张三", "一个钱包", "内侧刻字 ZX"));
        LostDtos.ItemView coach = service.detail(v.id(), "COACH");
        assertThat(coach.appearance()).isNull();
        assertThat(coach.photoRefs()).isNull();
        assertThat(coach.activeClaim()).isNotNull();
        assertThat(coach.activeClaim().privacyFeatures()).isNull();
        assertThat(coach.activeClaim().claimDesc()).isNull();
        assertThat(coach.activeClaim().reviews()).isEmpty();
        assertThat(coach.allowedActions()).isEmpty();

        // 前台可见外观但默认看不到照片
        LostDtos.ItemView front = service.detail(v.id(), "FRONT");
        assertThat(front.appearance()).isNotNull();
        assertThat(front.photoRefs()).isNull();
        // 经理显式调阅照片才给；不调阅时照片仍为空
        assertThat(service.reviewPack(v.id(), true).photoRefs()).containsExactly("photo://lost/test-1.jpg");
        assertThat(service.reviewPack(v.id(), false).photoRefs()).isNull();
    }

    // ---------- 2. 认领审核：不符不能通过 / 补证后可通过 ----------

    @Test
    void review_featureMismatchCannotApprove_thenNeedMoreResubmit() {
        LostDtos.ItemView v = registerAndSeal("ACCESSORY", "前台休息区", false, false);
        Long claimId = service.createClaim(v.id(),
                claimReq("MEMBER", 8L, null, "一个挂件", "内侧白色刻字 ZX")).activeClaimId();

        assertThatThrownBy(() -> service.review(claimId,
                reviewReq("MISMATCH", "MATCH", "MATCH", "APPROVE")))
                .isInstanceOf(BizException.class).hasMessageContaining("不符");

        LostDtos.ItemView needMore = service.review(claimId,
                reviewReq("MATCH", "MATCH", "UNKNOWN", "NEED_MORE"));
        assertThat(needMore.activeClaim().status()).isEqualTo("NEED_MORE");
        assertThat(needMore.activeClaim().reviews()).hasSize(1);

        service.supplement(claimId, new LostDtos.SupplementReq("补充购买记录截图编号", "前台-马倩"));
        LostDtos.ItemView approved = service.review(claimId,
                reviewReq("MATCH", "MATCH", "MATCH", "APPROVE"));
        assertThat(approved.status()).isEqualTo("TO_RETURN");
        assertThat(approved.activeClaim().reviews()).hasSize(2);
    }

    @Test
    void memberClaim_usesMemberArchive() {
        LostDtos.ItemView v = registerAndSeal("CLOTHING", "看台", false, false);
        LostDtos.ItemView claimed = service.createClaim(v.id(),
                claimReq("MEMBER", 2L, null, "一件外套", "内领手写名字"));
        assertThat(claimed.activeClaim().claimantName()).isEqualTo("林悦");
    }

    // ---------- 3. 双人现场交还 ----------

    @Test
    void handover_requiresTwoDistinctStaff_andSealed() {
        LostDtos.ItemView v = registerAndSeal("EQUIPMENT", "器材归还区", false, false);
        Long claimId = service.createClaim(v.id(),
                claimReq("VISITOR", null, "访客李四", "一件护具", "刻字 ZX")).activeClaimId();
        service.review(claimId, reviewReq("MATCH", "MATCH", "MATCH", "APPROVE"));
        service.startHandover(v.id(), "前台-马倩");

        // 两名确认人不能是同一人
        assertThatThrownBy(() -> service.completeHandover(v.id(),
                handoverReq("前台-马倩", "FRONT", "前台-马倩", "FRONT", "访客李四")))
                .isInstanceOf(BizException.class).hasMessageContaining("同一人");

        // 非当前保管人不能做第一确认人
        assertThatThrownBy(() -> service.completeHandover(v.id(),
                handoverReq("保管员-老周", "KEEPER", "苏禾", "COACH", "访客李四")))
                .isInstanceOf(BizException.class).hasMessageContaining("当前保管人");

        // 失败后状态没有推进，交接记录没有被重复生成
        assertThat(service.detail(v.id(), "FRONT").status()).isEqualTo("TO_RETURN");
        assertThat(handoverRepository.findTopByItemIdOrderByIdDesc(v.id()).orElseThrow().getStatus())
                .isEqualTo("PENDING");

        // 双人正确确认，闭环
        LostDtos.ItemView done = service.completeHandover(v.id(),
                handoverReq("前台-马倩", "FRONT", "保管员-老周", "KEEPER", "访客李四"));
        assertThat(done.status()).isEqualTo("RETURNED");
        assertThat(done.handover().status()).isEqualTo("DONE");
        assertThat(done.handover().witness()).isEqualTo("保管员-老周");

        // 重复交还被拒绝
        assertThatThrownBy(() -> service.completeHandover(v.id(),
                handoverReq("前台-马倩", "FRONT", "保管员-老周", "KEEPER", "访客李四")))
                .isInstanceOf(BizException.class).hasMessageContaining("请勿重复");
    }

    @Test
    void handover_receiverChanged_mustGoException() {
        LostDtos.ItemView v = registerAndSeal("OTHER", "走廊", false, false);
        Long claimId = service.createClaim(v.id(),
                claimReq("VISITOR", null, "访客王五", "物品", "刻字 ZX")).activeClaimId();
        service.review(claimId, reviewReq("MATCH", "MATCH", "MATCH", "APPROVE"));
        service.startHandover(v.id(), "前台-马倩");
        assertThatThrownBy(() -> service.completeHandover(v.id(),
                handoverReq("前台-马倩", "FRONT", "保管员-老周", "KEEPER", "代领人赵六")))
                .isInstanceOf(BizException.class).hasMessageContaining("异常");
    }

    // ---------- 4. 异常交接 ----------

    @Test
    void exception_resealThenDoubleHandoverStillRequired() {
        LostDtos.ItemView v = registerAndSeal("ELECTRONIC", "A10 箭道", false, false);
        Long claimId = service.createClaim(v.id(),
                claimReq("MEMBER", 2L, null, "耳机", "序列号 8842")).activeClaimId();
        service.review(claimId, reviewReq("MATCH", "MATCH", "UNKNOWN", "APPROVE"));
        service.startHandover(v.id(), "前台-马倩");

        LostDtos.ItemView ex = service.reportException(v.id(), new LostDtos.HandoverExceptionReq(
                "SEAL_DAMAGED", "封签边缘开裂", "前台-马倩", "FRONT"));
        assertThat(ex.status()).isEqualTo("EXCEPTION");
        assertThat(ex.handover().exceptionReason()).isEqualTo("SEAL_DAMAGED");
        assertThat(ex.activeClaim().status()).isEqualTo("EXCEPTION");
        assertThat(ex.activeClaim().reviews().get(0).decision()).isEqualTo("APPROVE");

        assertThatThrownBy(() -> service.completeHandover(v.id(),
                handoverReq("前台-马倩", "FRONT", "保管员-老周", "KEEPER", "林悦")))
                .isInstanceOf(BizException.class).hasMessageContaining("异常");

        LostDtos.ItemView resealed = service.resolveException(v.id(), new LostDtos.ResolveExceptionReq(
                "RESEAL", "核验实物一致，重贴封签", "值班经理-冯珊",
                "前台-马倩", "FRONT", "FRONT_DESK"));
        assertThat(resealed.status()).isEqualTo("TO_RETURN");
        assertThat(resealed.seals()).hasSize(2);
        // 处置后仍须双人交还
        assertThatThrownBy(() -> service.completeHandover(v.id(),
                handoverReq("前台-马倩", "FRONT", "前台-马倩", "FRONT", "林悦")))
                .isInstanceOf(BizException.class).hasMessageContaining("同一人");
        LostDtos.ItemView done = service.completeHandover(v.id(),
                handoverReq("前台-马倩", "FRONT", "保管员-老周", "KEEPER", "林悦"));
        assertThat(done.status()).isEqualTo("RETURNED");
    }

    @Test
    void exception_terminateClaim_cannotBecomeReturned() {
        LostDtos.ItemView v = registerAndSeal("OTHER", "测试公共区域", false, false);
        Long claimId = service.createClaim(v.id(),
                claimReq("VISITOR", null, "访客钱七", "物品", "刻字 ZX")).activeClaimId();
        service.review(claimId, reviewReq("MATCH", "MATCH", "MATCH", "APPROVE"));
        service.reportException(v.id(), new LostDtos.HandoverExceptionReq(
                "RECIPIENT_CHANGED", "来了一个未申报的代领人", "前台-马倩", "FRONT"));
        LostDtos.ItemView terminated = service.resolveException(v.id(), new LostDtos.ResolveExceptionReq(
                "TERMINATE", "无法核验代领关系，终止本次认领", "值班经理-冯珊",
                null, null, null));
        assertThat(terminated.status()).isEqualTo("IN_CUSTODY");
        assertThat(terminated.activeClaimId()).isNull();
        assertThat(terminated.claims().get(0).status()).isEqualTo("TERMINATED");

        // 已终止的申请不能再交还
        assertThatThrownBy(() -> service.startHandover(v.id(), "前台-马倩"))
                .isInstanceOf(BizException.class);
        // 物品可以由其他人重新发起认领
        LostDtos.ItemView second = service.createClaim(v.id(),
                claimReq("VISITOR", null, "真正失主", "物品，内侧刻字 ZX", "白色刻字 ZX"));
        assertThat(second.activeClaimId()).isNotNull();
    }

    // ---------- 5. 驳回为终态 ----------

    @Test
    void reject_isTerminal_butItemStaysInCustody() {
        LostDtos.ItemView v = registerAndSeal("OTHER", "测试公共区域", false, false);
        Long claimId = service.createClaim(v.id(),
                claimReq("VISITOR", null, "冒领人", "描述对不上", "无关特征")).activeClaimId();
        LostDtos.ItemView rejected = service.review(claimId,
                reviewReq("MISMATCH", "MISMATCH", "MISMATCH", "REJECT"));
        assertThat(rejected.status()).isEqualTo("IN_CUSTODY");
        assertThat(rejected.activeClaimId()).isNull();
        assertThatThrownBy(() -> service.review(claimId,
                reviewReq("MATCH", "MATCH", "MATCH", "APPROVE")))
                .isInstanceOf(BizException.class).hasMessageContaining("不能审核");
        assertThat(service.createClaim(v.id(),
                claimReq("VISITOR", null, "真正失主", "物品", "白色刻字 ZX")).activeClaimId()).isNotNull();
    }

    // ---------- 6. 查看开封后必须重封存 ----------

    @Test
    void inspect_thenMustReseal_doubleInspectBlocked() {
        LostDtos.ItemView v = registerAndSeal("OTHER", "测试公共区域", false, false);
        LostDtos.ItemView broken = service.inspect(v.id(), new LostDtos.InspectReq(
                "保管员-老周", "KEEPER", "INSPECT", "经理想核对外观"));
        assertThat(broken.sealState()).isEqualTo("BROKEN");
        assertThatThrownBy(() -> service.inspect(v.id(), new LostDtos.InspectReq(
                "保管员-老周", "KEEPER", "INSPECT", "再看一次")))
                .isInstanceOf(BizException.class).hasMessageContaining("重新封存");
        LostDtos.ItemView resealed = service.reseal(v.id(), new LostDtos.ResealReq(
                "保管员-老周", "KEEPER", "STORAGE", "保管员-老周", "外观与登记一致"));
        assertThat(resealed.seals()).hasSize(2);
        assertThat(resealed.sealState()).isEqualTo("SEALED");
    }

    // ---------- 7. 逾期扫描与处置分流 ----------

    @Test
    void markOverdue_thenDisposeNormalItemWithReceipt() {
        LostDtos.ItemView v = registerAndSeal("CLOTHING", "更衣室", false, false);
        expire(v.id());
        int count = service.markOverdue(new LostDtos.MarkOverdueReq("值班经理-冯珊"));
        assertThat(count).isGreaterThanOrEqualTo(1);
        LostDtos.ItemView overdue = service.detail(v.id(), "MANAGER");
        assertThat(overdue.status()).isEqualTo("TO_DISPOSE");

        // 必须留接收方凭证
        assertThatThrownBy(() -> service.dispose(v.id(), new LostDtos.DisposeReq(
                "DONATE", "公益回收机构", null, "", null, "值班经理-冯珊")))
                .isInstanceOf(BizException.class).hasMessageContaining("凭证");

        LostDtos.ItemView disposed = service.dispose(v.id(), new LostDtos.DisposeReq(
                "DONATE", "公益回收机构", "0571-1234", "DN-2026-09-001", "外套一包捐赠", "值班经理-冯珊"));
        assertThat(disposed.status()).isEqualTo("DISPOSED");
        assertThat(disposed.disposal().receiptNo()).isEqualTo("DN-2026-09-001");
    }

    @Test
    void highValueItem_disposeOnlyPolice() {
        LostDtos.ItemView v = registerAndSeal("JEWELRY", "前台休息区", true, false);
        expire(v.id());
        service.markOverdue(new LostDtos.MarkOverdueReq("值班经理-冯珊"));
        // 高价值物品不能捐赠 / 报废
        assertThatThrownBy(() -> service.dispose(v.id(), new LostDtos.DisposeReq(
                "DONATE", "公益回收机构", null, "DN-1", null, "值班经理-冯珊")))
                .isInstanceOf(BizException.class).hasMessageContaining("返还公安");
        assertThatThrownBy(() -> service.dispose(v.id(), new LostDtos.DisposeReq(
                "SCRAP", "报废单位", null, "SC-1", null, "值班经理-冯珊")))
                .isInstanceOf(BizException.class).hasMessageContaining("返还公安");
        // 返还公安且凭证齐全才成功
        LostDtos.ItemView disposed = service.dispose(v.id(), new LostDtos.DisposeReq(
                "POLICE", "站前派出所失物窗口", "0571-88001100", "POLICE-T-001", "金项链当面清点签收",
                "值班经理-冯珊"));
        assertThat(disposed.status()).isEqualTo("DISPOSED");
    }

    @Test
    void identityItem_disposeOnlyPolice() {
        LostDtos.ItemView v = registerAndSeal("WALLET", "馆门口", false, true);
        expire(v.id());
        service.markOverdue(new LostDtos.MarkOverdueReq("值班经理-冯珊"));
        assertThatThrownBy(() -> service.dispose(v.id(), new LostDtos.DisposeReq(
                "SCRAP", "报废单位", null, "SC-2", null, "值班经理-冯珊")))
                .isInstanceOf(BizException.class).hasMessageContaining("返还公安");
    }

    @Test
    void activeClaim_blocksOverdueScan() {
        // 有在途认领的物品即使过期也不进待处置
        LostDtos.ItemView v = registerAndSeal("OTHER", "测试公共区域", false, false);
        service.createClaim(v.id(), claimReq("VISITOR", null, "访客", "物品", "刻字 ZX"));
        expire(v.id());
        LostDtos.ItemView still = service.detail(v.id(), "FRONT");
        assertThat(still.status()).isEqualTo("IN_CUSTODY");
        // 扫描后仍在保管（在途认领未清空）
        service.markOverdue(new LostDtos.MarkOverdueReq("值班经理-冯珊"));
        assertThat(service.detail(v.id(), "FRONT").status()).isEqualTo("IN_CUSTODY");
    }
}
