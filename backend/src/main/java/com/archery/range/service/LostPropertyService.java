package com.archery.range.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.archery.range.common.BizException;
import com.archery.range.domain.Course;
import com.archery.range.domain.Lane;
import com.archery.range.domain.LostClaim;
import com.archery.range.domain.LostClaimReview;
import com.archery.range.domain.LostHandover;
import com.archery.range.domain.LostItem;
import com.archery.range.domain.LostItemDisposal;
import com.archery.range.domain.LostItemSeal;
import com.archery.range.domain.LostPropertyLog;
import com.archery.range.domain.Member;
import com.archery.range.domain.RangeDict;
import com.archery.range.dto.LostDtos;
import com.archery.range.repository.CourseRepository;
import com.archery.range.repository.LaneRepository;
import com.archery.range.repository.LostClaimRepository;
import com.archery.range.repository.LostClaimReviewRepository;
import com.archery.range.repository.LostHandoverRepository;
import com.archery.range.repository.LostItemDisposalRepository;
import com.archery.range.repository.LostItemRepository;
import com.archery.range.repository.LostItemSealRepository;
import com.archery.range.repository.LostPropertyLogRepository;
import com.archery.range.repository.MemberRepository;

/**
 * 失物保管与认领交接服务。
 *
 * 关键设计：
 * 1. 追加式事实 —— 原登记（类别 / 位置 / 时间 / 外观 / 照片 / 发现人）写入后没有任何修改入口；
 *    每次开封作废旧签、每次重封存 / 转移发新签；查看、转移、认领、审核、交还、异常、处置全部沿
 *    {@link LostPropertyLog} 同一条链追加，步骤失败不写链，重试靠物品状态 / 封签状态闸住，绝不重复出记录。
 * 2. 物品行串行 —— 所有变更动作先对 lost_item FOR UPDATE，再校验状态与封签态；
 *    已驳回 / 已终止的申请不能被改回已交还，终态物品（RETURNED / DISPOSED）拒绝一切推进动作。
 * 3. 双人交还不可跳过 —— 审核通过只到 TO_RETURN；现场交还必须当前保管人 + 另一名不同工作人员双人确认；
 *    封签破损 / 实物不符 / 领取人变更 / 保管人缺席转 EXCEPTION，原审核保留、交还暂停，
 *    经理处置（重新封存 / 补充核验 / 终止）后仍要回到双人交还，异常本身永远不会直接闭环成交还。
 * 4. 最小信息 —— 公示只给模糊类别 / 日期 / 区域；教练看不到照片、联系方式与认领证据；
 *    经理审核包只给核验必需信息，照片默认不展示（显式调阅仍只给经理并留痕）。
 * 5. 逾期分流 —— 高价值 / 带身份信息物品处置只能返还公安；任何处置都必须有接收方凭证。
 */
@Service
public class LostPropertyService {

    private static final DateTimeFormatter NO_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static final String FRONT = "FRONT";
    public static final String KEEPER = "KEEPER";
    public static final String COACH = "COACH";
    public static final String MANAGER = "MANAGER";
    public static final String MEMBER = "MEMBER";
    public static final String VISITOR = "VISITOR";

    private static final List<String> STAFF_ROLES = List.of(FRONT, KEEPER, COACH, MANAGER);
    private static final List<String> CUSTODY_ROLES = List.of(FRONT, KEEPER);
    private static final List<String> ITEM_TERMINAL = List.of(LostItem.RETURNED, LostItem.DISPOSED);

    private final LostItemRepository itemRepository;
    private final LostItemSealRepository sealRepository;
    private final LostClaimRepository claimRepository;
    private final LostClaimReviewRepository reviewRepository;
    private final LostHandoverRepository handoverRepository;
    private final LostItemDisposalRepository disposalRepository;
    private final LostPropertyLogRepository logRepository;
    private final MemberRepository memberRepository;
    private final LaneRepository laneRepository;
    private final CourseRepository courseRepository;

    public LostPropertyService(LostItemRepository itemRepository,
            LostItemSealRepository sealRepository,
            LostClaimRepository claimRepository,
            LostClaimReviewRepository reviewRepository,
            LostHandoverRepository handoverRepository,
            LostItemDisposalRepository disposalRepository,
            LostPropertyLogRepository logRepository,
            MemberRepository memberRepository,
            LaneRepository laneRepository,
            CourseRepository courseRepository) {
        this.itemRepository = itemRepository;
        this.sealRepository = sealRepository;
        this.claimRepository = claimRepository;
        this.reviewRepository = reviewRepository;
        this.handoverRepository = handoverRepository;
        this.disposalRepository = disposalRepository;
        this.logRepository = logRepository;
        this.memberRepository = memberRepository;
        this.laneRepository = laneRepository;
        this.courseRepository = courseRepository;
    }

    // ================= 查询：公示 / 台账 / 详情（角色脱敏） =================

    /** 认领人公示列表：只出现模糊类别、发现日期、馆内区域；终态 / 未封存的登记不公示 */
    @Transactional(readOnly = true)
    public List<LostDtos.PublicItemView> publicList() {
        return itemRepository.findByStatusInOrderByIdDesc(
                List.of(LostItem.IN_CUSTODY, LostItem.TO_RETURN, LostItem.EXCEPTION)).stream()
                .map(i -> new LostDtos.PublicItemView(
                        i.getId(), i.getItemNo(), i.getPublicCategory(),
                        RangeDict.lostPublicCategoryName(i.getPublicCategory()),
                        i.getFoundAt().format(DATE), areaDisplayName(i)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LostDtos.ItemRow> list(String role, String scope) {
        requireStaff(role);
        List<LostItem> items = switch (scope == null ? "all" : scope) {
            case "open" -> itemRepository.findByStatusNotInOrderByIdDesc(ITEM_TERMINAL);
            case "overdue" -> itemRepository.findByStatusInOrderByIdDesc(List.of(LostItem.TO_DISPOSE));
            case "custody" -> itemRepository.findByStatusInOrderByIdDesc(
                    List.of(LostItem.REGISTERED, LostItem.IN_CUSTODY));
            case "return" -> itemRepository.findByStatusInOrderByIdDesc(
                    List.of(LostItem.TO_RETURN, LostItem.EXCEPTION));
            default -> itemRepository.findAllByOrderByIdDesc();
        };
        return items.stream().map(i -> toRow(i, role)).toList();
    }

    /** 失物详情：按角色裁剪敏感字段与可见认领信息 */
    @Transactional(readOnly = true)
    public LostDtos.ItemView detail(Long id, String role) {
        requireStaff(role);
        LostItem item = requireItem(id);
        return toView(item, role, false);
    }

    /**
     * 经理审核包：完成核验所需的最少信息（物品外观 + 当前认领完整申报），
     * 照片默认不给；includePhoto=true 时才随包给出（仍仅经理）。
     */
    @Transactional(readOnly = true)
    public LostDtos.ItemView reviewPack(Long itemId, boolean includePhoto) {
        LostItem item = requireItem(itemId);
        return toView(item, MANAGER, includePhoto);
    }

    // ================= 失物登记 + 首次封存 =================

    @Transactional
    public LostDtos.ItemView register(LostDtos.RegisterItemReq req) {
        String operator = requireName(req.operator(), "登记前台");
        if (!RangeDict.isValidLostCategory(req.category())) {
            throw new BizException("物品类别不合法");
        }
        if (!List.of("LANE", "COURSE", "PUBLIC").contains(req.foundLocationType())) {
            throw new BizException("发现位置类型只能是：箭道 / 课程区 / 公共区域");
        }
        String area = requireName(req.foundArea(), "发现区域");
        LocalDateTime foundAt = req.foundAt();
        if (foundAt.isAfter(LocalDateTime.now())) {
            throw new BizException("发现时间不能晚于当前时间");
        }
        String appearance = requireName(req.appearance(), "外观特征");
        String finderName = requireName(req.finderName(), "发现人姓名");
        if (!List.of(MEMBER, "STAFF").contains(req.finderRole())) {
            throw new BizException("发现人身份只能是：会员 / 工作人员");
        }
        Long laneId = null;
        Long courseId = null;
        if ("LANE".equals(req.foundLocationType())) {
            laneId = requireLane(req.foundLaneId());
        } else if ("COURSE".equals(req.foundLocationType())) {
            courseId = requireCourse(req.foundCourseId());
        }
        boolean highValue = Boolean.TRUE.equals(req.highValueFlag());
        boolean identity = Boolean.TRUE.equals(req.identityFlag());
        String publicCategory = identity ? "ID_BEARING" : (highValue ? "VALUABLE" : "NORMAL");
        if (req.photoRefs() != null && req.photoRefs().trim().length() > 500) {
            throw new BizException("现场照片引用过长");
        }

        LocalDateTime now = LocalDateTime.now();
        LostItem item = new LostItem();
        item.setItemNo(nextItemNo());
        item.setCategory(req.category());
        item.setPublicCategory(publicCategory);
        item.setFoundLocationType(req.foundLocationType());
        item.setFoundLaneId(laneId);
        item.setFoundCourseId(courseId);
        item.setFoundArea(area);
        item.setFoundAt(foundAt);
        item.setAppearance(appearance);
        item.setPhotoRefs(trimToNull(req.photoRefs()));
        item.setHighValueFlag(highValue);
        item.setIdentityFlag(identity);
        item.setFinderName(finderName);
        item.setFinderContact(trimToNull(req.finderContact()));
        item.setFinderRole(req.finderRole());
        item.setRegisteredBy(operator);
        item.setStatus(LostItem.REGISTERED);
        // 保管期限先按类别给值，首次封存时正式生效（以封存时刻起算）
        item.setCustodyDeadline(deadlineFrom(now, highValue));
        item.setCreatedAt(now);
        itemRepository.save(item);

        String locText = locationText(req.foundLocationType(), laneId, courseId, area);
        log(item, null, null, LostPropertyLog.REGISTER, null, LostItem.REGISTERED, operator, FRONT,
                "失物登记：" + RangeDict.lostCategoryName(req.category()) + "；发现位置 " + locText
                        + "；发现时间 " + foundAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        + "；发现人 " + finderName + "（" + (MEMBER.equals(req.finderRole()) ? "会员" : "工作人员") + "）"
                        + (identity ? "；标记为带身份信息物品" : highValue ? "；标记为高价值物品" : "")
                        + "；已拍现场照片" + (item.getPhotoRefs() == null ? "（待补）" : ""));
        return toView(item, FRONT, false);
    }

    /** 值班前台生成封签并完成首次交接：REGISTERED → IN_CUSTODY */
    @Transactional
    public LostDtos.ItemView firstSeal(Long id, LostDtos.FirstSealReq req) {
        LostItem item = lockItem(id);
        if (!LostItem.REGISTERED.equals(item.getStatus())) {
            throw new BizException("物品 " + item.getItemNo() + " 当前为「"
                    + RangeDict.lostStatusName(item.getStatus()) + "」，首次封存只在登记后进行一次，请勿重复封存");
        }
        String operator = requireName(req.operator(), "操作前台");
        String custodian = requireName(req.custodian(), "保管人");
        String role = normalizeCustodyRole(req.custodianRole());
        String area = requireStorageArea(req.storageArea());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = deadlineFrom(now, item.isHighValueFlag());

        LostItemSeal seal = newSeal(item, 1, custodian, role, area, operator, now);
        item.setCurrentCustodian(custodian);
        item.setCurrentCustodianRole(role);
        item.setCurrentStorageArea(area);
        item.setCurrentSealId(seal.getId());
        item.setSealState(LostItem.SEALED);
        item.setCustodyDeadline(deadline);
        item.setStatus(LostItem.IN_CUSTODY);
        itemRepository.save(item);

        log(item, null, seal.getId(), LostPropertyLog.FIRST_SEAL, LostItem.REGISTERED, LostItem.IN_CUSTODY,
                operator, FRONT,
                "值班前台生成封签 " + seal.getSealNo() + " 并完成首次交接：保管人 " + custodian
                        + "（" + RangeDict.lostRoleName(role) + "），存放 " + RangeDict.storageAreaName(area)
                        + "，封签密封；保管期限至 " + deadline.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        return toView(item, FRONT, false);
    }

    // ================= 保管中：查看开封 / 重封存 / 转移（沿用同一条链） =================

    /** 工作人员查看：必须登记开封原因；当前封签由 SEALED 转 BROKEN（一次性），查看后须重封存 */
    @Transactional
    public LostDtos.ItemView inspect(Long id, LostDtos.InspectReq req) {
        LostItem item = lockItem(id);
        String role = normalizeStaffRole(req.operatorRole());
        if (!List.of(FRONT, KEEPER, MANAGER).contains(role)) {
            throw new BizException("只有前台 / 保管员 / 值班经理可以登记查看，教练与会员不得接触封存货品");
        }
        if (!List.of(LostItem.IN_CUSTODY, LostItem.TO_RETURN).contains(item.getStatus())) {
            throw new BizException("物品当前为「" + RangeDict.lostStatusName(item.getStatus())
                    + "」，不能查看开封");
        }
        // 工作人员查看固定按 INSPECT 开封；转移 / 交还 / 重封存核验开封由对应流程自动登记
        if (req.reason() != null && !req.reason().isBlank()
                && !LostItemSeal.REASON_INSPECT.equals(req.reason())) {
            throw new BizException("工作人员查看的开封原因只能是「查看」，转移 / 交还 / 重封存开封请走对应流程");
        }
        String reasonNote = requireName(req.reasonNote(), "开封查看原因说明");
        String operator = requireName(req.operator(), "查看人");
        LostItemSeal seal = requireCurrentSealed(item); // 已开封则拒绝重复查看，必须先重封存
        LocalDateTime now = LocalDateTime.now();
        breakSeal(seal, operator, now, LostItemSeal.REASON_INSPECT, reasonNote);
        item.setSealState(LostItem.BROKEN);
        itemRepository.save(item);
        log(item, item.getActiveClaimId(), seal.getId(), LostPropertyLog.UNSEAL, null, null,
                operator, role, "因工作人员查看开封：封签 " + seal.getSealNo()
                        + " 作废；查看原因：" + reasonNote + "；查看后必须重新封存才能继续保管 / 转移 / 交还");
        return toView(item, role, false);
    }

    /** 查看后重新封存：BROKEN → 新签 SEALED（不推进物品状态） */
    @Transactional
    public LostDtos.ItemView reseal(Long id, LostDtos.ResealReq req) {
        LostItem item = lockItem(id);
        String role = normalizeCustodyRole(req.custodianRole());
        if (!LostItem.BROKEN.equals(item.getSealState()) || item.getCurrentSealId() == null) {
            throw new BizException("只有已开封的物品需要重新封存；当前封签状态为「"
                    + RangeDict.sealStateName(item.getSealState()) + "」");
        }
        if (LostItem.RETURNED.equals(item.getStatus()) || LostItem.DISPOSED.equals(item.getStatus())) {
            throw new BizException("已交还 / 已处置物品不能再封存");
        }
        String operator = requireName(req.operator(), "封存人");
        String custodian = requireName(req.custodian(), "保管人");
        String area = requireStorageArea(req.storageArea());
        String note = requireName(req.note(), "重封存核验说明");
        LocalDateTime now = LocalDateTime.now();

        LostItemSeal old = sealRepository.findById(item.getCurrentSealId()).orElseThrow();
        if (LostItemSeal.SEALED.equals(old.getSealState())) {
            throw new BizException("当前封签仍密封，无需重封存");
        }
        old.setResealNote(note);
        sealRepository.save(old);
        int seq = sealRepository.countByItemId(id) + 1;
        LostItemSeal seal = newSeal(item, seq, custodian, role, area, operator, now);
        item.setCurrentCustodian(custodian);
        item.setCurrentCustodianRole(role);
        item.setCurrentStorageArea(area);
        item.setCurrentSealId(seal.getId());
        item.setSealState(LostItem.SEALED);
        itemRepository.save(item);
        log(item, item.getActiveClaimId(), seal.getId(), LostPropertyLog.RESEAL, null, null,
                operator, role, "重新封存：新封签 " + seal.getSealNo() + "（第 " + seq
                        + " 枚），保管人 " + custodian + "，存放 " + RangeDict.storageAreaName(area)
                        + "；核验情况：" + note);
        return toView(item, role, false);
    }

    /** 保管转移：开封旧签（TRANSFER）→ 核验 → 新保管人重封存；只能在保管中转移 */
    @Transactional
    public LostDtos.ItemView transfer(Long id, LostDtos.TransferReq req) {
        LostItem item = lockItem(id);
        String operatorRole = normalizeStaffRole(req.operatorRole());
        if (!LostItem.IN_CUSTODY.equals(item.getStatus())) {
            throw new BizException("只有保管中的物品可以转移保管，当前为「"
                    + RangeDict.lostStatusName(item.getStatus()) + "」");
        }
        String operator = requireName(req.operator(), "移交操作人");
        String toCustodian = requireName(req.toCustodian(), "新保管人");
        String toRole = normalizeCustodyRole(req.toCustodianRole());
        String toArea = requireStorageArea(req.toStorageArea());
        String note = requireName(req.note(), "转移原因与核验情况");
        LocalDateTime now = LocalDateTime.now();

        String fromCustodian = item.getCurrentCustodian();
        String fromArea = item.getCurrentStorageArea();
        if (toCustodian.equals(fromCustodian) && toArea.equals(fromArea)) {
            throw new BizException("新保管人与存放区域与当前一致，无需转移（重试不会重复生成交接记录）");
        }

        LostItemSeal old;
        if (LostItem.SEALED.equals(item.getSealState())) {
            old = requireCurrentSealed(item);
            breakSeal(old, operator, now, LostItemSeal.REASON_TRANSFER, note);
            log(item, null, old.getId(), LostPropertyLog.UNSEAL, null, null, operator, operatorRole,
                    "因保管转移开封：封签 " + old.getSealNo() + " 作废");
        } else {
            // 已开封（如刚做完查看）也允许直接转移，但必须以重封存新签收口
            old = sealRepository.findById(item.getCurrentSealId()).orElseThrow();
            old.setResealNote(note);
            sealRepository.save(old);
        }

        int seq = sealRepository.countByItemId(id) + 1;
        LostItemSeal seal = newSeal(item, seq, toCustodian, toRole, toArea, toCustodian, now);
        item.setCurrentCustodian(toCustodian);
        item.setCurrentCustodianRole(toRole);
        item.setCurrentStorageArea(toArea);
        item.setCurrentSealId(seal.getId());
        item.setSealState(LostItem.SEALED);
        itemRepository.save(item);
        log(item, null, seal.getId(), LostPropertyLog.TRANSFER, null, null, toCustodian, toRole,
                "保管转移：保管人 " + fromCustodian + " → " + toCustodian + "；存放区域 "
                        + RangeDict.storageAreaName(fromArea) + " → " + RangeDict.storageAreaName(toArea)
                        + "；核验与转移原因：" + note + "；重封存 " + seal.getSealNo());
        return toView(item, toRole, false);
    }

    // ================= 认领申报（会员 / 访客） =================

    @Transactional
    public LostDtos.ItemView createClaim(Long itemId, LostDtos.CreateClaimReq req) {
        LostItem item = lockItem(itemId);
        if (!LostItem.IN_CUSTODY.equals(item.getStatus())) {
            throw new BizException("只有保管中的物品可以登记认领申报，当前为「"
                    + RangeDict.lostStatusName(item.getStatus())
                    + "」（待交还 / 异常挂起已有在途认领，须先沿原认领处理）");
        }
        if (item.getActiveClaimId() != null) {
            LostClaim active = claimRepository.findById(item.getActiveClaimId()).orElse(null);
            throw new BizException("该物品已有一笔在途认领 " + (active == null ? "" : active.getClaimNo())
                    + "（" + RangeDict.claimStatusName(active == null ? "" : active.getStatus())
                    + "），须在其终态后才能发起新认领");
        }
        String operator = requireName(req.operator(), "录入前台");
        String type = validClaimantType(req.claimantType());
        Long memberId = null;
        String claimantName;
        String phone = trimToNull(req.claimantPhone());
        if ("MEMBER".equals(type)) {
            if (req.memberId() == null) {
                throw new BizException("会员认领请选择会员档案");
            }
            Member member = memberRepository.findById(req.memberId())
                    .orElseThrow(() -> new BizException("会员不存在：id=" + req.memberId()));
            if (Member.STATUS_MERGED.equals(member.getStatus())) {
                throw new BizException("会员卡 [" + member.getCardNo() + "] 已合并封存，请用保留档案发起认领");
            }
            memberId = member.getId();
            claimantName = member.getName();
            if (phone == null) {
                phone = member.getPhone();
            }
        } else {
            claimantName = requireName(req.claimantName(), "认领人姓名");
            if (phone == null) {
                throw new BizException("未建档访客认领请留下联系电话，便于审核结果通知");
            }
        }
        if (phone != null && !phone.matches("1[3-9]\\d{9}")) {
            throw new BizException("联系电话格式不正确（需为 11 位手机号）");
        }
        String desc = requireName(req.claimDesc(), "物品描述");
        if (req.lostTimeFrom() == null || req.lostTimeTo() == null) {
            throw new BizException("请填写自述遗失时间范围");
        }
        if (req.lostTimeTo().isBefore(req.lostTimeFrom())) {
            throw new BizException("遗失时间范围的止不能早于起");
        }
        String privacy = requireName(req.privacyFeatures(), "用于核验的隐私特征");
        Long laneId = req.possibleLaneId() == null ? null : requireLane(req.possibleLaneId());
        Long courseId = req.possibleCourseId() == null ? null : requireCourse(req.possibleCourseId());

        LocalDateTime now = LocalDateTime.now();
        LostClaim claim = new LostClaim();
        claim.setClaimNo(nextClaimNo());
        claim.setItem(item);
        claim.setClaimantType(type);
        claim.setMemberId(memberId);
        claim.setClaimantName(claimantName);
        claim.setClaimantPhone(phone);
        claim.setClaimDesc(desc);
        claim.setLostTimeFrom(req.lostTimeFrom());
        claim.setLostTimeTo(req.lostTimeTo());
        claim.setPossibleLaneId(laneId);
        claim.setPossibleCourseId(courseId);
        claim.setPossibleArea(trimToNull(req.possibleArea()));
        claim.setPrivacyFeatures(privacy);
        claim.setStatus(LostClaim.PENDING);
        claim.setCreatedBy(operator);
        claim.setCreatedAt(now);
        claimRepository.save(claim);

        item.setActiveClaimId(claim.getId());
        itemRepository.save(item);
        log(item, claim.getId(), null, LostPropertyLog.CLAIM_CREATE, null, null, operator, FRONT,
                ("MEMBER".equals(type) ? "会员 " : "访客 ") + claimantName
                        + " 提交认领申报：自述遗失时间 " + req.lostTimeFrom().format(DATE) + " ~ "
                        + req.lostTimeTo().format(DATE) + "；可能出现于 "
                        + possibleLocationText(laneId, courseId, req.possibleArea())
                        + "；已登记隐私特征与物品描述，提交值班经理审核");
        return toView(item, FRONT, false);
    }

    /** 认领人补证：仅 NEED_MORE 可补；追加到 supplementary，重新进入待审核（旧轮次结论保留） */
    @Transactional
    public LostDtos.ItemView supplement(Long claimId, LostDtos.SupplementReq req) {
        LostClaim claim = requireClaim(claimId);
        LostItem item = lockItem(claim.getItem().getId());
        if (!LostClaim.NEED_MORE.equals(claim.getStatus())) {
            throw new BizException("只有被要求补证的认领可以补充材料，当前为「"
                    + RangeDict.claimStatusName(claim.getStatus()) + "」");
        }
        String supplementary = requireName(req.supplementary(), "补充证明材料");
        String operator = requireName(req.operator(), "录入前台");
        claim.setSupplementary(supplementary);
        claim.setStatus(LostClaim.PENDING);
        claimRepository.save(claim);
        item.setActiveClaimId(claim.getId());
        itemRepository.save(item);
        log(item, claim.getId(), null, LostPropertyLog.CLAIM_CREATE, null, null, operator, FRONT,
                "认领人按第 " + reviewRepository.findByClaimIdOrderByReviewRoundAsc(claimId).size()
                        + " 轮审核要求补充证明材料并重新提交审核（首次申报保留不覆盖）");
        return toView(item, FRONT, false);
    }

    // ================= 值班经理审核：三项分项结论 =================

    @Transactional
    public LostDtos.ItemView review(Long claimId, LostDtos.ReviewReq req) {
        LostClaim claim = requireClaim(claimId);
        LostItem item = lockItem(claim.getItem().getId());
        if (!LostClaim.PENDING.equals(claim.getStatus())) {
            throw new BizException("认领 " + claim.getClaimNo() + " 当前为「"
                    + RangeDict.claimStatusName(claim.getStatus()) + "」，不能审核（已出结论的申请不可改写）");
        }
        String manager = requireName(req.manager(), "值班经理");
        String feature = validResult(req.featureResult());
        String timeplace = validResult(req.timeplaceResult());
        String support = validResult(req.supportResult());
        String decision = req.decision();
        if (!List.of(LostClaimReview.APPROVE, LostClaimReview.NEED_MORE, LostClaimReview.REJECT)
                .contains(decision)) {
            throw new BizException("审核终判只能是：通过 / 要求补证 / 驳回");
        }
        String note = requireName(req.reviewNote(), "审核意见");
        boolean includePhoto = Boolean.TRUE.equals(req.includePhoto());
        if (LostClaimReview.APPROVE.equals(decision)) {
            if (LostClaimReview.MISMATCH.equals(feature) || LostClaimReview.MISMATCH.equals(timeplace)) {
                throw new BizException("特征匹配或时间地点结论为「不符」时不能审核通过，请改判补证或驳回");
            }
            // 封签必须完好才允许审核通过进入现场交还（校验前置，不产生半成品审核记录）
            if (!LostItem.SEALED.equals(item.getSealState())) {
                throw new BizException("物品封签当前为已开封，须由保管人重新封存后才能审核通过进入交还");
            }
        }

        int round = reviewRepository.findByClaimIdOrderByReviewRoundAsc(claimId).size() + 1;
        LocalDateTime now = LocalDateTime.now();
        LostClaimReview review = new LostClaimReview();
        review.setClaim(claim);
        review.setReviewRound(round);
        review.setFeatureResult(feature);
        review.setTimeplaceResult(timeplace);
        review.setSupportResult(support);
        review.setFeatureNote(trimToNull(req.featureNote()));
        review.setTimeplaceNote(trimToNull(req.timeplaceNote()));
        review.setSupportNote(trimToNull(req.supportNote()));
        review.setDecision(decision);
        review.setReviewNote(note);
        review.setReviewedBy(manager);
        review.setReviewedAt(now);
        reviewRepository.save(review);

        String itemBefore = item.getStatus();
        if (LostClaimReview.APPROVE.equals(decision)) {
            claim.setStatus(LostClaim.APPROVED);
            claim.setDecidedBy(manager);
            claim.setDecidedAt(now);
            claimRepository.save(claim);
            item.setStatus(LostItem.TO_RETURN);
            item.setActiveClaimId(claim.getId());
            itemRepository.save(item);
            log(item, claim.getId(), null, LostPropertyLog.REVIEW, null, null, manager, MANAGER,
                    "第 " + round + " 轮审核：特征" + RangeDict.reviewResultName(feature)
                            + " / 时间地点" + RangeDict.reviewResultName(timeplace)
                            + " / 补充证明" + RangeDict.reviewResultName(support)
                            + (includePhoto ? "（审核中调阅了现场照片）" : ""));
            log(item, claim.getId(), null, LostPropertyLog.APPROVE, null, null, manager, MANAGER,
                    "审核通过：" + note);
            log(item, claim.getId(), null, LostPropertyLog.TO_RETURN, itemBefore, LostItem.TO_RETURN,
                    manager, MANAGER, "物品进入待交还，须由当前保管人 " + item.getCurrentCustodian()
                            + " 与另一名工作人员共同完成现场认领交接");
        } else if (LostClaimReview.NEED_MORE.equals(decision)) {
            claim.setStatus(LostClaim.NEED_MORE);
            claimRepository.save(claim);
            item.setActiveClaimId(claim.getId());
            itemRepository.save(item);
            log(item, claim.getId(), null, LostPropertyLog.REVIEW, null, null, manager, MANAGER,
                    "第 " + round + " 轮审核：特征" + RangeDict.reviewResultName(feature)
                            + " / 时间地点" + RangeDict.reviewResultName(timeplace)
                            + " / 补充证明" + RangeDict.reviewResultName(support));
            log(item, claim.getId(), null, LostPropertyLog.NEED_MORE, null, null, manager, MANAGER,
                    "要求补证：" + note + "（物品维持" + RangeDict.lostStatusName(item.getStatus())
                            + "，认领人补充后重新提交，旧结论保留）");
        } else {
            claim.setStatus(LostClaim.REJECTED);
            claim.setDecidedBy(manager);
            claim.setDecidedAt(now);
            claimRepository.save(claim);
            // 驳回释放物品：在途认领清空，物品仍按原状态继续保管（不修改任何登记事实）
            item.setActiveClaimId(null);
            itemRepository.save(item);
            log(item, claim.getId(), null, LostPropertyLog.REVIEW, null, null, manager, MANAGER,
                    "第 " + round + " 轮审核：特征" + RangeDict.reviewResultName(feature)
                            + " / 时间地点" + RangeDict.reviewResultName(timeplace)
                            + " / 补充证明" + RangeDict.reviewResultName(support));
            log(item, claim.getId(), null, LostPropertyLog.REJECT, null, null, manager, MANAGER,
                    "驳回认领：" + note + "（物品继续保管，驳回为终态，不能重新改成已交还；其他人可另行发起认领）");
        }
        return toView(item, MANAGER, includePhoto);
    }

    // ================= 现场双人交还与异常 =================

    /** 审核通过后建立现场交接（待双人确认）。审核通过即已进入 TO_RETURN，这里只准备交接单。 */
    @Transactional
    public LostDtos.ItemView startHandover(Long itemId, String operator) {
        LostItem item = lockItem(itemId);
        if (!LostItem.TO_RETURN.equals(item.getStatus())) {
            throw new BizException("只有审核通过、待交还的物品可以发起现场交接，当前为「"
                    + RangeDict.lostStatusName(item.getStatus()) + "」");
        }
        if (item.getActiveClaimId() == null) {
            throw new BizException("物品没有通过审核的认领，不能发起现场交还");
        }
        LostClaim claim = requireClaim(item.getActiveClaimId());
        if (!LostClaim.APPROVED.equals(claim.getStatus())) {
            throw new BizException("当前认领状态为「" + RangeDict.claimStatusName(claim.getStatus())
                    + "」，不能发起现场交还");
        }
        Optional<LostHandover> existing = handoverRepository.findTopByItemIdOrderByIdDesc(itemId);
        if (existing.isPresent() && existing.get().getClaim().getId().equals(claim.getId())) {
            throw new BizException("该认领已存在现场交接单，请勿重复建立（沿原交接单继续双人确认 / 异常处置）");
        }
        if (!LostItem.SEALED.equals(item.getSealState())) {
            throw new BizException("封签当前为已开封，须重新封存后再进行现场交还");
        }
        String op = requireName(operator, "前台");
        LocalDateTime now = LocalDateTime.now();
        LostHandover handover = new LostHandover();
        handover.setItem(item);
        handover.setClaim(claim);
        handover.setCustodian(item.getCurrentCustodian());
        handover.setCustodianRole(item.getCurrentCustodianRole());
        handover.setStatus(LostHandover.PENDING);
        handover.setCreatedBy(op);
        handover.setCreatedAt(now);
        handoverRepository.save(handover);
        return toView(item, FRONT, false);
    }

    /**
     * 双人现场交还：当前保管人 + 另一名工作人员共同确认，核验证件、记录实际领取人与交还时间。
     * 成功才推进物品到 RETURNED；任一项校验失败整体回滚，交接单不产生完成记录。
     */
    @Transactional
    public LostDtos.ItemView completeHandover(Long itemId, LostDtos.HandoverCompleteReq req) {
        LostItem item = lockItem(itemId);
        String operator = requireName(req.operator(), "主持交还前台");
        if (LostItem.RETURNED.equals(item.getStatus())) {
            throw new BizException("物品已交还，请勿重复确认（交接事实不可改写）");
        }
        LostHandover handover = handoverRepository.findTopByItemIdOrderByIdDesc(itemId)
                .orElseThrow(() -> new BizException("请先发起现场交接，再进行双人确认"));
        if (LostHandover.DONE.equals(handover.getStatus())) {
            throw new BizException("现场交接已完成，请勿重复确认");
        }
        if (LostHandover.EXCEPTION.equals(handover.getStatus())) {
            throw new BizException("交接处于异常挂起，须先由值班经理完成异常处置，再沿本单重新双人交还");
        }
        if (!LostItem.TO_RETURN.equals(item.getStatus())) {
            throw new BizException("物品当前为「" + RangeDict.lostStatusName(item.getStatus()) + "」，不能交还");
        }
        LostClaim claim = requireClaim(handover.getClaim().getId());
        if (!List.of(LostClaim.APPROVED, LostClaim.EXCEPTION).contains(claim.getStatus())) {
            throw new BizException("认领当前为「" + RangeDict.claimStatusName(claim.getStatus())
                    + "」，不能交还（驳回 / 终止的申请永远不能改成已交还）");
        }

        // 双人确认校验
        String custodian = requireName(req.custodian(), "当前保管人");
        String custodianRole = normalizeCustodyRole(req.custodianRole());
        String witness = requireName(req.witness(), "见证工作人员");
        String witnessRole = normalizeStaffRole(req.witnessRole());
        if (custodian.equals(witness)) {
            throw new BizException("双人交还必须由当前保管人与另一名工作人员完成，两名确认人不能是同一人");
        }
        if (!custodian.equals(item.getCurrentCustodian())) {
            throw new BizException("第一确认人必须是当前保管人 " + item.getCurrentCustodian()
                    + "；保管人无法到场时须转异常交接（不能由他人顶替直接交还）");
        }
        if (!custodianRole.equals(item.getCurrentCustodianRole())) {
            throw new BizException("保管人岗位与登记不一致，请核对后重试");
        }
        LostDtos.ReceiverReq receiver = req.receiver();
        if (receiver == null) {
            throw new BizException("请核验实际领取人证件");
        }
        String receiverName = requireName(receiver.name(), "实际领取人");
        if (!RangeDict.isValidIdVerifyType(receiver.idType())) {
            throw new BizException("证件核验方式只能是：身份证 / 会员卡 / 驾照 / 其他证件");
        }
        String idNo = requireName(receiver.idNo(), "证件核验标识");

        // 实际领取人与申报认领人不同：必须先走异常（领取人临时变更），不能在正常双人交还里直接放行
        if (!receiverName.equals(claim.getClaimantName())) {
            throw new BizException("实际领取人 " + receiverName + " 与审核通过的认领人 "
                    + claim.getClaimantName() + " 不一致，必须先登记异常交接（领取人临时变更），"
                    + "由值班经理补充核验后再双人交还");
        }
        if (!LostItem.SEALED.equals(item.getSealState())) {
            throw new BizException("封签已开封，须重新封存并确认完好后才能交还（异常请按异常流程登记）");
        }

        LocalDateTime now = LocalDateTime.now();
        // 现场交还开封当前封签（RETURN）
        LostItemSeal seal = requireCurrentSealed(item);
        breakSeal(seal, operator, now, LostItemSeal.REASON_RETURN,
                "双人现场交还：保管人 " + custodian + " + 见证人 " + witness + "，领取人 " + receiverName);

        handover.setCustodian(custodian);
        handover.setCustodianRole(custodianRole);
        handover.setWitness(witness);
        handover.setWitnessRole(witnessRole);
        handover.setReceiverName(receiverName);
        handover.setReceiverIdType(receiver.idType());
        handover.setReceiverIdNo(idNo);
        handover.setStatus(LostHandover.DONE);
        handover.setConfirmedBy(operator);
        handover.setHandoverAt(now);
        handoverRepository.save(handover);

        String before = item.getStatus();
        item.setStatus(LostItem.RETURNED);
        item.setSealState(LostItem.BROKEN);
        item.setCurrentCustodian(null);
        item.setCurrentCustodianRole(null);
        item.setCurrentStorageArea(null);
        itemRepository.save(item);

        claim.setStatus(LostClaim.RETURNED);
        claimRepository.save(claim);
        log(item, claim.getId(), seal.getId(), LostPropertyLog.UNSEAL, null, null, operator, FRONT,
                "因现场交还开封：封签 " + seal.getSealNo() + " 开拆");
        log(item, claim.getId(), null, LostPropertyLog.HANDOVER_DONE, before, LostItem.RETURNED,
                operator, FRONT,
                "双人现场交还完成：当前保管人 " + custodian + "（" + RangeDict.lostRoleName(custodianRole)
                        + "）与另一名工作人员 " + witness + "（" + RangeDict.lostRoleName(witnessRole)
                        + "）共同确认；实际领取人 " + receiverName + "，证件核验方式 "
                        + RangeDict.idVerifyTypeName(receiver.idType()) + "（标识 " + idNo + "）；交还时间 "
                        + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        return toView(item, FRONT, false);
    }

    /** 现场异常上报：封签破损 / 实物不符 / 领取人变更 / 保管人缺席 → 暂停交还，原审核结果保留 */
    @Transactional
    public LostDtos.ItemView reportException(Long itemId, LostDtos.HandoverExceptionReq req) {
        LostItem item = lockItem(itemId);
        String role = normalizeStaffRole(req.operatorRole());
        String reason = req.reason();
        if (!List.of(LostHandover.SEAL_DAMAGED, LostHandover.ITEM_MISMATCH,
                LostHandover.RECIPIENT_CHANGED, LostHandover.CUSTODIAN_ABSENT).contains(reason)) {
            throw new BizException("异常原因只能是：封签破损 / 实物与登记不符 / 领取人临时变更 / 保管人无法到场");
        }
        String detail = requireName(req.detail(), "异常情况说明");
        String operator = requireName(req.operator(), "上报人");

        LostClaim claim = item.getActiveClaimId() == null ? null
                : requireClaim(item.getActiveClaimId());
        // 允许两种入口：已建交接单（TO_RETURN 现场）；尚未建单但在 TO_RETURN 也可直接登记异常（如保管人缺席）
        LostHandover handover = handoverRepository.findTopByItemIdOrderByIdDesc(itemId).orElseGet(() -> {
            if (!LostItem.TO_RETURN.equals(item.getStatus()) || claim == null
                    || !LostClaim.APPROVED.equals(claim.getStatus())) {
                throw new BizException("只有待交还、认领审核通过的物品可以登记现场交接异常");
            }
            LostHandover h = new LostHandover();
            h.setItem(item);
            h.setClaim(claim);
            h.setCustodian(item.getCurrentCustodian() == null ? operator : item.getCurrentCustodian());
            h.setCustodianRole(item.getCurrentCustodianRole() == null ? role : item.getCurrentCustodianRole());
            h.setCreatedBy(operator);
            h.setCreatedAt(LocalDateTime.now());
            return h;
        });
        if (LostHandover.DONE.equals(handover.getStatus())) {
            throw new BizException("交接已完成，不能再登记异常");
        }
        if (LostHandover.EXCEPTION.equals(handover.getStatus())) {
            throw new BizException("该交接已处于异常挂起，请勿重复登记，等待值班经理处置");
        }

        LocalDateTime now = LocalDateTime.now();
        handover.setStatus(LostHandover.EXCEPTION);
        handover.setExceptionReason(reason);
        handover.setExceptionDetail(detail);
        handover.setExceptionBy(operator);
        handover.setExceptionAt(now);
        handoverRepository.save(handover);

        String before = item.getStatus();
        item.setStatus(LostItem.EXCEPTION);
        // 封签破损 / 实物不符：封签不再可信，置为已开封（无正常开封日志，异常本身即记录事实）
        if (LostHandover.SEAL_DAMAGED.equals(reason) || LostHandover.ITEM_MISMATCH.equals(reason)) {
            item.setSealState(LostItem.BROKEN);
            if (item.getCurrentSealId() != null) {
                LostItemSeal seal = sealRepository.findById(item.getCurrentSealId()).orElse(null);
                if (seal != null && LostItemSeal.SEALED.equals(seal.getSealState())) {
                    seal.setSealState(LostItemSeal.BROKEN);
                    seal.setBrokenBy(operator);
                    seal.setBrokenAt(now);
                    seal.setBreakReason(LostItemSeal.REASON_RESEAL);
                    seal.setResealNote("异常：" + RangeDict.handoverExceptionName(reason) + "，封签停止使用");
                    sealRepository.save(seal);
                }
            }
        }
        itemRepository.save(item);
        if (claim != null) {
            claim.setStatus(LostClaim.EXCEPTION);
            claimRepository.save(claim);
        }
        log(item, claim == null ? null : claim.getId(), null, LostPropertyLog.EXCEPTION, before,
                LostItem.EXCEPTION, operator, role,
                "异常交接：" + RangeDict.handoverExceptionName(reason) + "；" + detail
                        + "。暂停交还，原审核结果保留，等待值班经理选择重新封存 / 补充核验 / 终止本次认领"
                        + "（任何处置都不能跳过后续双人交还）");
        return toView(item, role, false);
    }

    /** 值班经理异常处置：RESEAL 重新封存 / RECHECK 补充核验 / TERMINATE 终止本次认领 */
    @Transactional
    public LostDtos.ItemView resolveException(Long itemId, LostDtos.ResolveExceptionReq req) {
        LostItem item = lockItem(itemId);
        String manager = requireName(req.manager(), "值班经理");
        String resolution = req.resolution();
        if (!List.of(LostHandover.RESEAL, LostHandover.RECHECK, LostHandover.TERMINATE).contains(resolution)) {
            throw new BizException("处置方式只能是：重新封存 / 补充核验 / 终止本次认领");
        }
        String detail = requireName(req.detail(), "处置说明");
        if (!LostItem.EXCEPTION.equals(item.getStatus())) {
            throw new BizException("只有异常挂起的物品需要异常处置，当前为「"
                    + RangeDict.lostStatusName(item.getStatus()) + "」");
        }
        LostHandover handover = handoverRepository.findTopByItemIdOrderByIdDesc(itemId)
                .orElseThrow(() -> new BizException("缺少异常交接记录"));
        if (!LostHandover.EXCEPTION.equals(handover.getStatus())) {
            throw new BizException("交接单当前不是异常挂起状态");
        }
        LostClaim claim = requireClaim(handover.getClaim().getId());
        LocalDateTime now = LocalDateTime.now();

        handover.setResolution(resolution);
        handover.setResolutionDetail(detail);
        handover.setResolvedBy(manager);
        handover.setResolvedAt(now);

        String itemBefore = item.getStatus();
        switch (resolution) {
            case LostHandover.RESEAL -> {
                String custodian = requireName(req.custodian(), "重封存保管人");
                String custodianRole = normalizeCustodyRole(req.custodianRole());
                String area = requireStorageArea(req.storageArea());
                // 重新封存发新签；旧异常签已停用
                int seq = sealRepository.countByItemId(itemId) + 1;
                LostItemSeal seal = newSeal(item, seq, custodian, custodianRole, area, manager, now);
                item.setCurrentCustodian(custodian);
                item.setCurrentCustodianRole(custodianRole);
                item.setCurrentStorageArea(area);
                item.setCurrentSealId(seal.getId());
                item.setSealState(LostItem.SEALED);
                item.setStatus(LostItem.TO_RETURN);
                claim.setStatus(LostClaim.APPROVED);
                handover.setStatus(LostHandover.PENDING);
                log(item, claim.getId(), seal.getId(), LostPropertyLog.RESEAL, null, null, manager, MANAGER,
                        "异常处置-重新封存：" + detail + "；新封签 " + seal.getSealNo() + "，保管人 "
                                + custodian + "，存放 " + RangeDict.storageAreaName(area));
            }
            case LostHandover.RECHECK -> {
                // 补充核验：保持暂停，物品仍异常挂起；核验完成后由经理重新封存才能回到双人交还
                item.setStatus(LostItem.EXCEPTION);
                claim.setStatus(LostClaim.EXCEPTION);
                handover.setStatus(LostHandover.EXCEPTION);
                log(item, claim.getId(), null, LostPropertyLog.EXCEPTION_RESOLVE, null, null, manager, MANAGER,
                        "异常处置-补充核验：" + detail + "；原审核结果保留，核验完成后须重新封存并再走双人交还");
            }
            default -> {
                // TERMINATE：终止本次认领（终态，不能改回已交还）；物品回到保管中等待其他人认领或逾期处置。
                // 本交接单保留异常挂起 + 终止处置事实（不回退状态）；新认领另建新交接单，互不覆盖。
                claim.setStatus(LostClaim.TERMINATED);
                claim.setTerminatedBy(manager);
                claim.setTerminatedAt(now);
                claim.setTerminateReason(detail);
                item.setActiveClaimId(null);
                // 终止后实物必须处于保管态：若封签已破（破损 / 不符），要求经理先以重新封存路径收口；
                // 仅领取人变更 / 保管人缺席（封签仍完好）可直接回到保管中。
                if (!LostItem.SEALED.equals(item.getSealState())) {
                    throw new BizException("封签处于已开封状态，不能直接终止认领；请先选择「重新封存」"
                            + "由新封签收口后，再终止本次认领");
                }
                item.setStatus(LostItem.IN_CUSTODY);
                log(item, claim.getId(), null, LostPropertyLog.EXCEPTION_RESOLVE, itemBefore,
                        LostItem.IN_CUSTODY, manager, MANAGER,
                        "异常处置-终止本次认领：" + detail + "；认领转为终态（不能重新改成已交还），"
                                + "物品回到保管中，其他人可另行发起认领，逾期则进入处置");
            }
        }
        handoverRepository.save(handover);
        claimRepository.save(claim);
        itemRepository.save(item);
        return toView(item, MANAGER, false);
    }

    // ================= 逾期处置 =================

    /** 值班经理把超过保管期限、无在途认领的物品转入待处置清单 */
    @Transactional
    public int markOverdue(LostDtos.MarkOverdueReq req) {
        String manager = requireName(req.manager(), "值班经理");
        LocalDateTime now = LocalDateTime.now();
        List<LostItem> candidates = itemRepository.findByStatusInOrderByIdDesc(
                List.of(LostItem.IN_CUSTODY)).stream()
                .filter(i -> !i.getCustodyDeadline().isAfter(now) && i.getActiveClaimId() == null)
                .toList();
        int marked = 0;
        for (LostItem item : candidates) {
            LostItem locked = lockItem(item.getId());
            if (!LostItem.IN_CUSTODY.equals(locked.getStatus())
                    || locked.getCustodyDeadline().isAfter(now) || locked.getActiveClaimId() != null) {
                continue; // 并发下状态已变化则跳过，不产生重复轨迹
            }
            locked.setStatus(LostItem.TO_DISPOSE);
            locked.setMarkedOverdueBy(manager);
            locked.setMarkedOverdueAt(now);
            itemRepository.save(locked);
            log(locked, null, null, LostPropertyLog.MARK_OVERDUE, LostItem.IN_CUSTODY, LostItem.TO_DISPOSE,
                    manager, MANAGER, "保管期限 "
                            + locked.getCustodyDeadline().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                            + " 已过且无人认领，转入待处置清单"
                            + (locked.isHighValueFlag() || locked.isIdentityFlag()
                                    ? "（高价值 / 带身份信息物品只能返还公安）" : ""));
            marked += 1;
        }
        return marked;
    }

    /** 登记处置结果：高价值 / 身份物品只能公安移交；必须留接收方凭证 */
    @Transactional
    public LostDtos.ItemView dispose(Long itemId, LostDtos.DisposeReq req) {
        LostItem item = lockItem(itemId);
        String manager = requireName(req.manager(), "值班经理");
        if (!LostItem.TO_DISPOSE.equals(item.getStatus())) {
            throw new BizException("只有待处置清单内的物品可以登记处置结果，当前为「"
                    + RangeDict.lostStatusName(item.getStatus()) + "」");
        }
        String result = req.result();
        if (!List.of(LostItemDisposal.POLICE, LostItemDisposal.DONATE, LostItemDisposal.SCRAP)
                .contains(result)) {
            throw new BizException("处置结果只能是：返还公安 / 捐赠 / 报废");
        }
        if ((item.isHighValueFlag() || item.isIdentityFlag()) && !LostItemDisposal.POLICE.equals(result)) {
            throw new BizException((item.isHighValueFlag() ? "高价值物品" : "带身份信息物品")
                    + "必须采用返还公安路径，不能捐赠或报废");
        }
        String receiver = requireName(req.receiverName(), "接收方名称");
        String receiptNo = requireName(req.receiptNo(), "接收方凭证编号");
        LocalDateTime now = LocalDateTime.now();

        // 处置前封签开拆留痕（RESEAL 原因：处置核验）
        if (LostItem.SEALED.equals(item.getSealState()) && item.getCurrentSealId() != null) {
            LostItemSeal seal = sealRepository.findById(item.getCurrentSealId()).orElse(null);
            if (seal != null && LostItemSeal.SEALED.equals(seal.getSealState())) {
                breakSeal(seal, manager, now, LostItemSeal.REASON_RESEAL,
                        "逾期处置开拆核验：" + RangeDict.disposalResultName(result));
                log(item, null, seal.getId(), LostPropertyLog.UNSEAL, null, null, manager, MANAGER,
                        "因逾期处置开拆核验，封签 " + seal.getSealNo() + " 作废");
            }
        }

        LostItemDisposal disposal = new LostItemDisposal();
        disposal.setItem(item);
        disposal.setResult(result);
        disposal.setReceiverName(receiver);
        disposal.setReceiverContact(trimToNull(req.receiverContact()));
        disposal.setReceiptNo(receiptNo);
        disposal.setReceiptNote(trimToNull(req.receiptNote()));
        disposal.setDisposedBy(manager);
        disposal.setDisposedAt(now);
        disposalRepository.save(disposal);

        String before = item.getStatus();
        item.setStatus(LostItem.DISPOSED);
        item.setSealState(LostItem.BROKEN);
        item.setCurrentCustodian(null);
        item.setCurrentCustodianRole(null);
        item.setCurrentStorageArea(null);
        itemRepository.save(item);
        log(item, null, null, LostPropertyLog.DISPOSE, before, LostItem.DISPOSED, manager, MANAGER,
                "逾期处置：" + RangeDict.disposalResultName(result) + "；接收方 " + receiver
                        + "；凭证编号 " + receiptNo
                        + (disposal.getReceiverContact() == null ? "" : "；联系方式 " + disposal.getReceiverContact()));
        return toView(item, MANAGER, false);
    }

    // ================= 出参组装（角色脱敏的核心） =================

    private LostDtos.ItemRow toRow(LostItem item, String role) {
        LostClaim active = item.getActiveClaimId() == null ? null
                : claimRepository.findById(item.getActiveClaimId()).orElse(null);
        boolean overdue = LostItem.IN_CUSTODY.equals(item.getStatus())
                && item.getCustodyDeadline() != null
                && item.getCustodyDeadline().isBefore(LocalDateTime.now());
        return new LostDtos.ItemRow(
                item.getId(), item.getItemNo(), item.getCategory(),
                RangeDict.lostCategoryName(item.getCategory()),
                item.getPublicCategory(), RangeDict.lostPublicCategoryName(item.getPublicCategory()),
                item.getFoundArea(), item.getFoundAt(), item.isHighValueFlag(), item.isIdentityFlag(),
                item.getStatus(), RangeDict.lostStatusName(item.getStatus()),
                item.getCurrentCustodian(),
                item.getCurrentStorageArea() == null ? "" : RangeDict.storageAreaName(item.getCurrentStorageArea()),
                item.getSealState(), RangeDict.sealStateName(item.getSealState()),
                item.getActiveClaimId(),
                active == null ? null : active.getStatus(),
                active == null ? null : RangeDict.claimStatusName(active.getStatus()),
                item.getCustodyDeadline(), overdue, allowedActions(item, role));
    }

    private LostDtos.ItemView toView(LostItem item, String role, boolean includePhoto) {
        List<LostItemSeal> seals = sealRepository.findByItemIdOrderBySealSeqAsc(item.getId());
        List<LostPropertyLog> logs = logRepository.findByItemIdOrderByIdAsc(item.getId());

        // 照片：只有工作人员，且仅经理在显式调阅时给出；教练永不给
        List<String> photos = null;
        if (List.of(FRONT, KEEPER, MANAGER).contains(role)) {
            boolean unlocked = MANAGER.equals(role) && includePhoto;
            if (unlocked && item.getPhotoRefs() != null) {
                photos = Arrays.stream(item.getPhotoRefs().split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
            }
        }

        List<LostClaim> allClaims = claimRepository.findByItemIdOrderByIdDesc(item.getId());
        LostClaim activeEntity = item.getActiveClaimId() == null ? null
                : allClaims.stream().filter(c -> c.getId().equals(item.getActiveClaimId())).findFirst()
                        .orElse(null);
        LostDtos.ClaimView active = activeEntity == null ? null : toClaimView(activeEntity, role);
        // 历史认领只给经理 / 前台看到编号与状态摘要（不含证据）
        List<LostDtos.ClaimView> claims = allClaims.stream()
                .map(c -> toClaimView(c, MANAGER.equals(role) || FRONT.equals(role) || KEEPER.equals(role)
                        ? role : COACH))
                .toList();

        LostDtos.HandoverView handover = handoverRepository.findTopByItemIdOrderByIdDesc(item.getId())
                .map(this::toHandoverView).orElse(null);
        LostDtos.DisposalView disposal = disposalRepository.findAll().stream()
                .filter(d -> d.getItem().getId().equals(item.getId())).findFirst()
                .map(this::toDisposalView).orElse(null);

        boolean overdue = LostItem.IN_CUSTODY.equals(item.getStatus())
                && item.getCustodyDeadline() != null
                && item.getCustodyDeadline().isBefore(LocalDateTime.now());

        // 外观特征：教练不可见；前台 / 保管员 / 经理可见（审核与核验需要）
        String appearance = COACH.equals(role) ? null : item.getAppearance();
        String finderContact = COACH.equals(role) ? null : item.getFinderContact();

        return new LostDtos.ItemView(
                item.getId(), item.getItemNo(), item.getCategory(),
                RangeDict.lostCategoryName(item.getCategory()),
                item.getPublicCategory(), RangeDict.lostPublicCategoryName(item.getPublicCategory()),
                item.getFoundLocationType(), RangeDict.foundLocationTypeName(item.getFoundLocationType()),
                item.getFoundLaneId(), laneNo(item.getFoundLaneId()),
                item.getFoundCourseId(), courseName(item.getFoundCourseId()),
                item.getFoundArea(), item.getFoundAt(), appearance, photos,
                MANAGER.equals(role) && includePhoto,
                item.isHighValueFlag(), item.isIdentityFlag(),
                item.getFinderName(), finderContact, item.getFinderRole(),
                item.getRegisteredBy(), item.getStatus(), RangeDict.lostStatusName(item.getStatus()),
                item.getCurrentCustodian(), item.getCurrentCustodianRole(),
                item.getCurrentStorageArea(),
                item.getCurrentStorageArea() == null ? null : RangeDict.storageAreaName(item.getCurrentStorageArea()),
                item.getCurrentSealId(), item.getSealState(), RangeDict.sealStateName(item.getSealState()),
                item.getCustodyDeadline(), overdue, item.getMarkedOverdueBy(), item.getMarkedOverdueAt(),
                item.getCreatedAt(), item.getActiveClaimId(), active, claims,
                seals.stream().map(this::toSealView).toList(),
                handover, disposal,
                logs.stream().map(this::toLogView).toList(),
                allowedActions(item, role), role);
    }

    private LostDtos.ClaimView toClaimView(LostClaim claim, String role) {
        // 隐私特征：仅经理审核可见；联系方式：前台（通知）/ 经理可见；
        // 认领描述 / 补证 / 审核结论：仅前台与经理可见；教练、保管员、会员都不接触认领证据。
        boolean frontOrManager = FRONT.equals(role) || MANAGER.equals(role);
        String privacy = MANAGER.equals(role) ? claim.getPrivacyFeatures() : null;
        String phone = frontOrManager ? claim.getClaimantPhone() : null;
        String desc = frontOrManager ? claim.getClaimDesc() : null;
        String supplementary = frontOrManager ? claim.getSupplementary() : null;
        List<LostDtos.ReviewView> reviews = frontOrManager
                ? reviewRepository.findByClaimIdOrderByReviewRoundAsc(claim.getId()).stream()
                        .map(this::toReviewView).toList()
                : List.of();
        return new LostDtos.ClaimView(
                claim.getId(), claim.getClaimNo(), claim.getItem().getId(),
                claim.getClaimantType(), RangeDict.claimantTypeName(claim.getClaimantType()),
                claim.getMemberId(), claim.getClaimantName(), phone, desc,
                claim.getLostTimeFrom(), claim.getLostTimeTo(),
                claim.getPossibleLaneId(), laneNo(claim.getPossibleLaneId()),
                claim.getPossibleCourseId(), courseName(claim.getPossibleCourseId()),
                claim.getPossibleArea(), privacy, supplementary,
                claim.getStatus(), RangeDict.claimStatusName(claim.getStatus()),
                claim.getCreatedBy(), claim.getCreatedAt(),
                claim.getDecidedBy(), claim.getDecidedAt(),
                claim.getTerminateReason(), claim.getTerminatedBy(), claim.getTerminatedAt(),
                reviews);
    }

    private LostDtos.ReviewView toReviewView(LostClaimReview r) {
        return new LostDtos.ReviewView(
                r.getId(), r.getReviewRound(),
                r.getFeatureResult(), RangeDict.reviewResultName(r.getFeatureResult()),
                r.getTimeplaceResult(), RangeDict.reviewResultName(r.getTimeplaceResult()),
                r.getSupportResult(), RangeDict.reviewResultName(r.getSupportResult()),
                r.getFeatureNote(), r.getTimeplaceNote(), r.getSupportNote(),
                r.getDecision(), RangeDict.reviewDecisionName(r.getDecision()),
                r.getReviewNote(), r.getReviewedBy(), r.getReviewedAt());
    }

    private LostDtos.SealView toSealView(LostItemSeal s) {
        return new LostDtos.SealView(
                s.getId(), s.getSealNo(), s.getSealSeq(), s.getCustodian(), s.getCustodianRole(),
                s.getStorageArea(), RangeDict.storageAreaName(s.getStorageArea()),
                s.getSealState(), RangeDict.sealStateName(s.getSealState()),
                s.getSealedBy(), s.getSealedAt(), s.getBrokenBy(), s.getBrokenAt(),
                s.getBreakReason(),
                s.getBreakReason() == null ? null : RangeDict.sealBreakReasonName(s.getBreakReason()),
                s.getResealNote());
    }

    private LostDtos.HandoverView toHandoverView(LostHandover h) {
        return new LostDtos.HandoverView(
                h.getId(), h.getCustodian(), h.getCustodianRole(), h.getWitness(), h.getWitnessRole(),
                h.getReceiverName(), h.getReceiverIdType(),
                h.getReceiverIdType() == null ? null : RangeDict.idVerifyTypeName(h.getReceiverIdType()),
                h.getReceiverIdNo(), h.getStatus(), RangeDict.handoverStatusName(h.getStatus()),
                h.getConfirmedBy(), h.getHandoverAt(),
                h.getExceptionReason(),
                h.getExceptionReason() == null ? null : RangeDict.handoverExceptionName(h.getExceptionReason()),
                h.getExceptionDetail(), h.getExceptionBy(), h.getExceptionAt(),
                h.getResolution(),
                h.getResolution() == null ? null : RangeDict.exceptionResolutionName(h.getResolution()),
                h.getResolutionDetail(), h.getResolvedBy(), h.getResolvedAt(), h.getCreatedAt());
    }

    private LostDtos.DisposalView toDisposalView(LostItemDisposal d) {
        return new LostDtos.DisposalView(
                d.getId(), d.getResult(), RangeDict.disposalResultName(d.getResult()),
                d.getReceiverName(), d.getReceiverContact(), d.getReceiptNo(), d.getReceiptNote(),
                d.getDisposedBy(), d.getDisposedAt());
    }

    private LostDtos.ChainLogView toLogView(LostPropertyLog l) {
        return new LostDtos.ChainLogView(
                l.getId(), l.getClaimId(), l.getSealId(), l.getAction(),
                RangeDict.lostActionName(l.getAction()),
                l.getBeforeStatus(), l.getBeforeStatus() == null ? null : RangeDict.lostStatusName(l.getBeforeStatus()),
                l.getAfterStatus(), l.getAfterStatus() == null ? null : RangeDict.lostStatusName(l.getAfterStatus()),
                l.getOperator(), l.getRole(), RangeDict.lostRoleName(l.getRole()),
                l.getDetail(), l.getCreatedAt());
    }

    /**
     * 当前角色在物品当前状态下可执行的动作。页面只渲染这些动作；后端每个动作仍各自强校验（纵深防御）。
     */
    private List<String> allowedActions(LostItem item, String role) {
        List<String> actions = new ArrayList<>();
        String status = item.getStatus();
        boolean sealed = LostItem.SEALED.equals(item.getSealState());
        switch (status) {
            case LostItem.REGISTERED -> {
                if (FRONT.equals(role)) {
                    actions.add("FIRST_SEAL");
                }
            }
            case LostItem.IN_CUSTODY -> {
                if (FRONT.equals(role)) {
                    actions.add("CREATE_CLAIM");
                }
                if (List.of(FRONT, KEEPER).contains(role)) {
                    if (sealed) {
                        actions.add("INSPECT");
                        actions.add("TRANSFER");
                    } else {
                        actions.add("RESEAL");
                    }
                }
                if (MANAGER.equals(role)) {
                    if (sealed) {
                        actions.add("INSPECT");
                    } else {
                        actions.add("RESEAL");
                    }
                    actions.add("MARK_OVERDUE");
                }
                if (item.getActiveClaimId() != null) {
                    LostClaim active = claimRepository.findById(item.getActiveClaimId()).orElse(null);
                    if (active != null && LostClaim.NEED_MORE.equals(active.getStatus()) && FRONT.equals(role)) {
                        actions.add("SUPPLEMENT");
                    }
                    if (active != null && LostClaim.PENDING.equals(active.getStatus()) && MANAGER.equals(role)) {
                        actions.add("REVIEW");
                    }
                }
            }
            case LostItem.TO_RETURN -> {
                if (FRONT.equals(role)) {
                    actions.add("START_HANDOVER");
                    actions.add("COMPLETE_HANDOVER");
                    actions.add("REPORT_EXCEPTION");
                }
                if (KEEPER.equals(role)) {
                    actions.add("REPORT_EXCEPTION");
                }
                if (MANAGER.equals(role)) {
                    actions.add("REPORT_EXCEPTION");
                }
            }
            case LostItem.EXCEPTION -> {
                if (MANAGER.equals(role)) {
                    actions.add("RESOLVE_EXCEPTION");
                }
            }
            case LostItem.TO_DISPOSE -> {
                if (MANAGER.equals(role)) {
                    actions.add("DISPOSE");
                }
            }
            default -> {
                // RETURNED / DISPOSED 终态无动作
            }
        }
        return actions;
    }

    // ================= 内部工具 =================

    private void requireStaff(String role) {
        if (role == null || !STAFF_ROLES.contains(role)) {
            throw new BizException("该视图仅对工作人员开放（前台 / 保管员 / 教练 / 值班经理）");
        }
    }

    private String normalizeStaffRole(String role) {
        if (!STAFF_ROLES.contains(role)) {
            throw new BizException("工作人员岗位只能是：前台 / 保管员 / 教练 / 值班经理");
        }
        return role;
    }

    private String normalizeCustodyRole(String role) {
        if (!CUSTODY_ROLES.contains(role)) {
            throw new BizException("保管人岗位只能是：前台 / 保管员");
        }
        return role;
    }

    private String validClaimantType(String type) {
        if (!List.of("MEMBER", "VISITOR").contains(type)) {
            throw new BizException("认领人类型只能是：现有会员 / 未建档访客");
        }
        return type;
    }

    private String validResult(String result) {
        if (!List.of(LostClaimReview.MATCH, LostClaimReview.MISMATCH, LostClaimReview.UNKNOWN)
                .contains(result)) {
            throw new BizException("分项审核结论只能是：匹配 / 不符 / 待核");
        }
        return result;
    }

    private String requireStorageArea(String area) {
        if (!List.of("FRONT_DESK", "SAFE", "STORAGE").contains(area)) {
            throw new BizException("存放区域只能是：前台失物柜 / 贵重物品保险柜 / 仓库货架");
        }
        return area;
    }

    private Long requireLane(Long laneId) {
        if (laneId == null) {
            throw new BizException("发现位置为箭道时请选择具体箭道");
        }
        laneRepository.findById(laneId).orElseThrow(() -> new BizException("所选箭道不存在，请刷新后重试"));
        return laneId;
    }

    private Long requireCourse(Long courseId) {
        if (courseId == null) {
            throw new BizException("发现位置为课程区时请选择具体课程");
        }
        courseRepository.findById(courseId).orElseThrow(() -> new BizException("所选课程不存在，请刷新后重试"));
        return courseId;
    }

    private LostItem lockItem(Long id) {
        return itemRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("失物不存在：id=" + id));
    }

    private LostItem requireItem(Long id) {
        return itemRepository.findById(id)
                .orElseThrow(() -> new BizException("失物不存在：id=" + id));
    }

    private LostClaim requireClaim(Long id) {
        return claimRepository.findById(id)
                .orElseThrow(() -> new BizException("认领申请不存在：id=" + id));
    }

    private LostItemSeal requireCurrentSealed(LostItem item) {
        if (item.getCurrentSealId() == null) {
            throw new BizException("物品没有当前封签，请先完成封存");
        }
        LostItemSeal seal = sealRepository.findByIdForUpdate(item.getCurrentSealId()).orElseThrow();
        if (!LostItemSeal.SEALED.equals(seal.getSealState())) {
            throw new BizException("当前封签 " + seal.getSealNo() + " 已开封，须先重新封存（不能重复开封 / 重复生成记录）");
        }
        return seal;
    }

    private void breakSeal(LostItemSeal seal, String by, LocalDateTime at, String reason, String note) {
        seal.setSealState(LostItemSeal.BROKEN);
        seal.setBrokenBy(by);
        seal.setBrokenAt(at);
        seal.setBreakReason(reason);
        if (note != null) {
            seal.setResealNote(note);
        }
        sealRepository.save(seal);
    }

    private LostItemSeal newSeal(LostItem item, int seq, String custodian, String role,
            String area, String sealedBy, LocalDateTime now) {
        LostItemSeal seal = new LostItemSeal();
        seal.setItem(item);
        seal.setSealNo(nextSealNo());
        seal.setSealSeq(seq);
        seal.setCustodian(custodian);
        seal.setCustodianRole(role);
        seal.setStorageArea(area);
        seal.setSealState(LostItemSeal.SEALED);
        seal.setSealedBy(sealedBy);
        seal.setSealedAt(now);
        sealRepository.save(seal);
        return seal;
    }

    private void log(LostItem item, Long claimId, Long sealId, String action,
            String before, String after, String operator, String role, String detail) {
        LostPropertyLog entry = new LostPropertyLog();
        entry.setItem(item);
        entry.setClaimId(claimId);
        entry.setSealId(sealId);
        entry.setAction(action);
        entry.setBeforeStatus(before);
        entry.setAfterStatus(after);
        entry.setOperator(operator);
        entry.setRole(role);
        entry.setDetail(detail);
        entry.setCreatedAt(LocalDateTime.now());
        logRepository.save(entry);
    }

    private LocalDateTime deadlineFrom(LocalDateTime from, boolean highValue) {
        return from.plusDays(highValue ? RangeDict.LOST_KEEP_DAYS_HIGH_VALUE : RangeDict.LOST_KEEP_DAYS_NORMAL);
    }

    private String nextItemNo() {
        String no = "L" + LocalDateTime.now().format(NO_STAMP);
        int seq = 1;
        while (itemRepository.existsByItemNo(no)) {
            no = "L" + LocalDateTime.now().format(NO_STAMP) + seq;
            seq += 1;
        }
        return no;
    }

    private String nextSealNo() {
        String no = "S" + LocalDateTime.now().format(NO_STAMP);
        int seq = 1;
        while (sealRepository.existsBySealNo(no)) {
            no = "S" + LocalDateTime.now().format(NO_STAMP) + seq;
            seq += 1;
        }
        return no;
    }

    private String nextClaimNo() {
        String no = "CL" + LocalDateTime.now().format(NO_STAMP);
        int seq = 1;
        while (claimRepository.existsByClaimNo(no)) {
            no = "CL" + LocalDateTime.now().format(NO_STAMP) + seq;
            seq += 1;
        }
        return no;
    }

    private String locationText(String type, Long laneId, Long courseId, String area) {
        return switch (type) {
            case "LANE" -> "箭道 " + laneNo(laneId);
            case "COURSE" -> "课程区 " + courseName(courseId);
            default -> "公共区域 " + area;
        };
    }

    private String possibleLocationText(Long laneId, Long courseId, String area) {
        if (laneId != null) {
            return "箭道 " + laneNo(laneId);
        }
        if (courseId != null) {
            return "课程 " + courseName(courseId);
        }
        return area == null ? "未指明" : area;
    }

    private String areaDisplayName(LostItem item) {
        // 公示只到馆内区域粒度：箭道 / 课程区不显示具体道号与课程名
        return switch (item.getFoundLocationType()) {
            case "LANE" -> "箭道区域";
            case "COURSE" -> "课程教学区";
            default -> item.getFoundArea();
        };
    }

    private String laneNo(Long laneId) {
        if (laneId == null) {
            return null;
        }
        return laneRepository.findById(laneId).map(Lane::getLaneNo).orElse("(箭道已删除)");
    }

    private String courseName(Long courseId) {
        if (courseId == null) {
            return null;
        }
        return courseRepository.findById(courseId).map(Course::getCourseName).orElse("(课程已删除)");
    }

    private String requireName(String value, String who) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            throw new BizException("请填写" + who);
        }
        if (v.length() > 500) {
            throw new BizException(who + "内容过长");
        }
        return v;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    /** 表单选项（箭道 / 课程 / 词典） */
    @Transactional(readOnly = true)
    public Map<String, Object> options() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("categories", List.of(
                Map.of("code", "WALLET", "name", RangeDict.lostCategoryName("WALLET")),
                Map.of("code", "ELECTRONIC", "name", RangeDict.lostCategoryName("ELECTRONIC")),
                Map.of("code", "JEWELRY", "name", RangeDict.lostCategoryName("JEWELRY")),
                Map.of("code", "CLOTHING", "name", RangeDict.lostCategoryName("CLOTHING")),
                Map.of("code", "EQUIPMENT", "name", RangeDict.lostCategoryName("EQUIPMENT")),
                Map.of("code", "ACCESSORY", "name", RangeDict.lostCategoryName("ACCESSORY")),
                Map.of("code", "OTHER", "name", RangeDict.lostCategoryName("OTHER"))));
        options.put("lanes", laneRepository.findAllByOrderByLaneNoAsc().stream()
                .map(l -> Map.<String, Object>of("id", l.getId(), "laneNo", l.getLaneNo()))
                .toList());
        options.put("courses", courseRepository.findAllByOrderByClassTimeAsc().stream()
                .map(c -> Map.<String, Object>of("id", c.getId(), "name", c.getCourseName()))
                .toList());
        options.put("storageAreas", List.of("FRONT_DESK", "SAFE", "STORAGE").stream()
                .map(a -> Map.of("code", a, "name", RangeDict.storageAreaName(a))).toList());
        options.put("idTypes", List.of("ID_CARD", "MEMBER_CARD", "DRIVING_LICENSE", "OTHER").stream()
                .map(a -> Map.of("code", a, "name", RangeDict.idVerifyTypeName(a))).toList());
        options.put("keepDaysNormal", RangeDict.LOST_KEEP_DAYS_NORMAL);
        options.put("keepDaysHighValue", RangeDict.LOST_KEEP_DAYS_HIGH_VALUE);
        return options;
    }
}
