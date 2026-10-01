package com.archery.range.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.archery.range.common.BizException;
import com.archery.range.domain.Course;
import com.archery.range.domain.Equipment;
import com.archery.range.domain.GuardianConsent;
import com.archery.range.domain.GuardianConsentCourse;
import com.archery.range.domain.GuardianConsentEquipment;
import com.archery.range.domain.GuardianConsentLane;
import com.archery.range.domain.GuardianConsentPickup;
import com.archery.range.domain.GuardianConsentVersion;
import com.archery.range.domain.Lane;
import com.archery.range.domain.Member;
import com.archery.range.domain.MinorVisit;
import com.archery.range.domain.MinorVisitLog;
import com.archery.range.domain.MinorVisitResource;
import com.archery.range.domain.RangeDict;
import com.archery.range.dto.GuardianDtos;
import com.archery.range.repository.CourseRepository;
import com.archery.range.repository.EquipmentRepository;
import com.archery.range.repository.GuardianConsentCourseRepository;
import com.archery.range.repository.GuardianConsentEquipmentRepository;
import com.archery.range.repository.GuardianConsentLaneRepository;
import com.archery.range.repository.GuardianConsentPickupRepository;
import com.archery.range.repository.GuardianConsentRepository;
import com.archery.range.repository.GuardianConsentVersionRepository;
import com.archery.range.repository.LaneRepository;
import com.archery.range.repository.MemberRepository;
import com.archery.range.repository.MinorVisitLogRepository;
import com.archery.range.repository.MinorVisitRepository;
import com.archery.range.repository.MinorVisitResourceRepository;

/**
 * 未成年会员到馆现场交接服务。
 *
 * 阶段：PENDING_PICKUP 待接管 → TRAINING 训练中 → PENDING_RETURN 待交还 → RETURNED 已交还。
 *
 * 关键设计：
 * 1. 两道授权闸 —— 接管时核对「当前有效授权 + 实际接管人身份 + 授权接管人名单」，钉住版本基线；
 *    训练开始前把课程 / 箭道 / 器材作为一组整体校验，任一不被该版本覆盖即整组拒绝，不留部分放行。
 * 2. 基线漂移不篡改事实 —— 接管后授权换版 / 暂停 / 撤销由授权侧置冻结位；这里读到冻结位即阻止
 *    签到后的进一步放行，工作人员 recheck 按新基线重新核对后才可继续；已开始的训练走异常处置至安全交还。
 * 3. 阶段串行 —— 交接单行 FOR UPDATE 串行接管 / 放行 / 交还，多人同时确认只有一个结果生效，
 *    重复 / 过期确认被拒绝并回显所依据的版本；未完成交还不能静默关闭，也不能开启下一次到馆。
 */
@Service
public class MinorVisitService {

    private static final DateTimeFormatter NO_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final List<String> OPEN_STATUSES =
            List.of(MinorVisit.PENDING_PICKUP, MinorVisit.TRAINING, MinorVisit.PENDING_RETURN);

    private final MinorVisitRepository visitRepository;
    private final MinorVisitResourceRepository resourceRepository;
    private final MinorVisitLogRepository logRepository;
    private final MemberRepository memberRepository;
    private final GuardianConsentRepository consentRepository;
    private final GuardianConsentVersionRepository versionRepository;
    private final GuardianConsentCourseRepository courseScopeRepository;
    private final GuardianConsentLaneRepository laneScopeRepository;
    private final GuardianConsentEquipmentRepository equipScopeRepository;
    private final GuardianConsentPickupRepository pickupRepository;
    private final CourseRepository courseRepository;
    private final LaneRepository laneRepository;
    private final EquipmentRepository equipmentRepository;
    private final GuardianConsentService consentService;

    public MinorVisitService(MinorVisitRepository visitRepository,
            MinorVisitResourceRepository resourceRepository,
            MinorVisitLogRepository logRepository,
            MemberRepository memberRepository,
            GuardianConsentRepository consentRepository,
            GuardianConsentVersionRepository versionRepository,
            GuardianConsentCourseRepository courseScopeRepository,
            GuardianConsentLaneRepository laneScopeRepository,
            GuardianConsentEquipmentRepository equipScopeRepository,
            GuardianConsentPickupRepository pickupRepository,
            CourseRepository courseRepository,
            LaneRepository laneRepository,
            EquipmentRepository equipmentRepository,
            GuardianConsentService consentService) {
        this.visitRepository = visitRepository;
        this.resourceRepository = resourceRepository;
        this.logRepository = logRepository;
        this.memberRepository = memberRepository;
        this.consentRepository = consentRepository;
        this.versionRepository = versionRepository;
        this.courseScopeRepository = courseScopeRepository;
        this.laneScopeRepository = laneScopeRepository;
        this.equipScopeRepository = equipScopeRepository;
        this.pickupRepository = pickupRepository;
        this.courseRepository = courseRepository;
        this.laneRepository = laneRepository;
        this.equipmentRepository = equipmentRepository;
        this.consentService = consentService;
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public List<GuardianDtos.VisitSummary> list(boolean openOnly, Long memberId) {
        List<MinorVisit> visits;
        if (memberId != null) {
            visits = openOnly
                    ? visitRepository.findByMemberIdAndStatusInOrderByIdDesc(memberId, OPEN_STATUSES)
                    : visitRepository.findByMemberIdOrderByIdDesc(memberId);
        } else {
            visits = openOnly
                    ? visitRepository.findByStatusInOrderByIdDesc(OPEN_STATUSES)
                    : visitRepository.findAllByOrderByIdDesc();
        }
        return visits.stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public GuardianDtos.VisitView detail(Long id) {
        MinorVisit visit = requireVisit(id);
        return toView(visit);
    }

    // ---------------- 建立到馆交接：当前授权无效直接拦在建单环节 ----------------

    @Transactional
    public GuardianDtos.VisitView create(GuardianDtos.CreateVisitReq req) {
        String operator = requireName(req.operator(), "前台");
        Member member = memberRepository.findById(req.memberId())
                .orElseThrow(() -> new BizException("会员不存在：id=" + req.memberId()));
        if (!member.isMinorFlag()) {
            throw new BizException("会员 [" + member.getName() + "] 为成年会员，使用成年会员流程，无需现场监护交接");
        }
        if (Member.STATUS_MERGED.equals(member.getStatus())) {
            throw new BizException("会员卡 [" + member.getCardNo() + "] 已合并封存，不能建立到馆交接");
        }
        // 上一次交接未完成，不能开启下一次到馆
        List<MinorVisit> openVisits =
                visitRepository.findByMemberIdAndStatusInOrderByIdDesc(member.getId(), OPEN_STATUSES);
        if (!openVisits.isEmpty()) {
            MinorVisit last = openVisits.get(0);
            throw new BizException("会员 [" + member.getName() + "] 存在未完成的到馆交接 " + last.getVisitNo()
                    + "（" + RangeDict.visitStatusName(last.getStatus())
                    + "），须完成安全交还后才能开启下一次到馆");
        }
        // 建单即核对当前有效授权（无效 / 暂停 / 撤销 / 过期都给出明确拦截原因）
        GuardianConsentVersion effective = consentService.requireEffectiveVersion(member);
        GuardianConsent chain = consentRepository.findById(effective.getConsent().getId()).orElseThrow();

        LocalDateTime now = LocalDateTime.now();
        MinorVisit visit = new MinorVisit();
        visit.setVisitNo(nextVisitNo());
        visit.setMember(member);
        visit.setStatus(MinorVisit.PENDING_PICKUP);
        visit.setConsentId(chain.getId());
        visit.setBaselineVersionId(effective.getId());
        visit.setBaselineFingerprint(effective.getContentFingerprint());
        visit.setBaselineFrozen(false);
        visit.setCreatedBy(operator);
        visit.setCreatedAt(now);
        visitRepository.save(visit);
        log(visit, "CREATE", null, operator, "FRONT",
                "建立到馆交接：当前授权 " + chain.getConsentNo() + " v" + effective.getVersionNo()
                        + " 有效（" + effective.getEffectiveFrom() + " 至 " + effective.getEffectiveUntil()
                        + "），等待接管人到场接管");
        return toView(visit);
    }

    // ---------------- 现场接管：核对授权 + 接管人身份 + 名单 + 声明资源 ----------------

    @Transactional
    public GuardianDtos.VisitView pickup(Long id, GuardianDtos.PickupReq req) {
        // 加锁顺序与授权侧联动冻结保持一致：授权链 → 版本 → 交接单，避免交叉等待死锁
        MinorVisit read = requireVisit(id);
        GuardianConsent chain = consentRepository.findByIdForUpdate(read.getConsentId())
                .orElseThrow(() -> new BizException("授权链不存在，请联系值班经理"));
        GuardianConsentVersion version = versionRepository.findByIdForUpdate(read.getBaselineVersionId())
                .orElseThrow(() -> new BizException("基线授权版本不存在，请联系值班经理"));
        MinorVisit visit = lockVisit(id);
        String operator = requireName(req.operator(), "前台");
        if (!MinorVisit.PENDING_PICKUP.equals(visit.getStatus())) {
            throw new BizException("交接单 " + visit.getVisitNo() + " 当前为「"
                    + RangeDict.visitStatusName(visit.getStatus()) + "」，不能重复接管");
        }
        if (visit.isBaselineFrozen()) {
            // 建单后、接管前授权已变化：该单不再可信，引导取消后按当前授权重新建单
            log(visit, "BLOCK", visit.getStatus(), operator, "FRONT",
                    "接管被拦截：建单后授权基线已变化（" + safe(visit.getFreezeReason())
                            + "），请取消本草稿交接并按当前授权重新建单");
            throw new BizException("交接单 " + visit.getVisitNo()
                    + " 建立后授权基线已变化，不能按旧授权接管；请取消本单后按当前授权重新建立到馆交接");
        }
        // 接管瞬间再次核对授权仍有效（建单后可能已被暂停 / 撤销 / 取代）
        try {
            assertCurrentEffective(chain, version);
        } catch (BizException e) {
            log(visit, "BLOCK", visit.getStatus(), operator, "FRONT",
                    "接管被拦截：" + e.getMessage());
            throw e;
        }
        if (!version.getContentFingerprint().equals(visit.getBaselineFingerprint())) {
            // 正常不可达：版本内容不可变。留作篡改防线。
            log(visit, "BLOCK", visit.getStatus(), operator, "FRONT",
                    "接管被拦截：授权 v" + version.getVersionNo() + " 内容指纹与建单基线不一致");
            throw new BizException("授权 v" + version.getVersionNo() + " 内容与建单时基线不一致，请刷新后重新核对");
        }

        // 实际接管人身份核验：必须在版本接管人名单内（姓名 + 证件标识一致）
        GuardianDtos.PersonReq person = req.person();
        if (person == null) {
            throw new BizException("请核验收人身份信息");
        }
        String name = requireName(person.name(), "接管人");
        String idNo = requireName(person.idNo(), "接管人证件标识");
        String phone = requirePhone(person.phone(), "接管人手机号");
        if (!RangeDict.isValidGuardianRelation(person.relation())) {
            throw new BizException("接管人关系只能是：父亲 / 母亲 / 祖父母 / 其他法定监护人");
        }
        GuardianConsentPickup matched = pickupRepository.findByVersionId(version.getId()).stream()
                .filter(p -> p.getPersonName().equals(name) && p.getIdNo().equals(idNo))
                .findFirst()
                .orElse(null);
        if (matched == null) {
            String reason = "接管人 " + name + "（证件标识 " + idNo + "）不在授权 v"
                    + version.getVersionNo() + " 的接管人名单内";
            log(visit, "BLOCK", visit.getStatus(), operator, "FRONT",
                    "接管被拦截：" + reason + "；禁止进入训练流程");
            throw new BizException(reason + "，不能接管。请监护人修改授权接管人名单（发布新版本重新确认）"
                    + "或由值班经理按异常流程处置");
        }

        // 声明本次拟用资源：去重、存在性整组校验（尚未比对授权范围，范围在训练放行前整组校验）
        List<Long> courseIds = distinct(req.courseIds());
        List<Long> laneIds = distinct(req.laneIds());
        List<Long> equipIds = distinct(req.equipmentIds());
        LocalDateTime now = LocalDateTime.now();
        if (!courseIds.isEmpty()) {
            List<Course> courses = courseRepository.findAllById(courseIds);
            if (courses.size() != courseIds.size()) {
                throw new BizException("声明的课程中有不存在的项目，请刷新后重试（接管未完成，可重试不产生重复记录）");
            }
        }
        if (!laneIds.isEmpty() && laneRepository.findAllById(laneIds).size() != laneIds.size()) {
            throw new BizException("声明的箭道中有不存在的道号，请刷新后重试（接管未完成，可重试不产生重复记录）");
        }
        if (!equipIds.isEmpty() && equipmentRepository.findAllById(equipIds).size() != equipIds.size()) {
            throw new BizException("声明的器材中有不存在的编号，请刷新后重试（接管未完成，可重试不产生重复记录）");
        }

        // 接管成立：钉住实际接管人与基线，进入训练中
        visit.setPickupPersonName(name);
        visit.setPickupPersonIdno(idNo);
        visit.setPickupPersonPhone(phone);
        visit.setPickupPersonRelation(person.relation());
        visit.setPickupOperator(operator);
        visit.setPickupAt(now);
        visit.setStatus(MinorVisit.TRAINING);
        visitRepository.save(visit);

        for (Long courseId : courseIds) {
            Course course = courseRepository.findById(courseId).orElseThrow();
            saveResource(visit, MinorVisitResource.COURSE, courseId, course.getCourseName(), now);
        }
        for (Long laneId : laneIds) {
            Lane lane = laneRepository.findById(laneId).orElseThrow();
            saveResource(visit, MinorVisitResource.LANE, laneId, lane.getLaneNo(), now);
        }
        for (Long equipId : equipIds) {
            Equipment equip = equipmentRepository.findById(equipId).orElseThrow();
            saveResource(visit, MinorVisitResource.EQUIPMENT, equipId, equip.getEquipCode(), now);
        }

        log(visit, "PICKUP", MinorVisit.PENDING_PICKUP, operator, "FRONT",
                "接管完成：实际接管人 " + name + "（" + RangeDict.guardianRelationName(person.relation())
                        + "，证件 " + idNo + "）与授权 v" + version.getVersionNo() + " 接管人名单核对一致；"
                        + "当前授权有效，基线已钉住；登记拟用资源 " + courseIds.size() + " 门课程 / "
                        + laneIds.size() + " 条箭道 / " + equipIds.size() + " 件器材，"
                        + "训练开始前须整组校验通过才能放行");
        return toView(visit);
    }

    // ---------------- 训练开始前整组放行：课程 / 箭道 / 器材必须全部被基线版本覆盖 ----------------

    /**
     * 训练开始前整组放行：课程 / 箭道 / 器材必须全部被基线版本覆盖。
     * 拦截以 noRollbackFor 提交：资源台账绝不留下部分放行，但 BLOCK / FREEZE 轨迹必须留痕。
     */
    @Transactional(noRollbackFor = com.archery.range.common.GuardianBlockedException.class)
    public GuardianDtos.VisitView release(Long id, GuardianDtos.ReleaseReq req) {
        // 加锁顺序与授权侧冻结联动一致：授权链 → 基线版本 → 交接单（避免交叉等待死锁）
        MinorVisit read = requireVisit(id);
        GuardianConsent chain = consentRepository.findByIdForUpdate(read.getConsentId())
                .orElseThrow(() -> new BizException("授权链不存在"));
        GuardianConsentVersion version = versionRepository.findByIdForUpdate(read.getBaselineVersionId())
                .orElseThrow(() -> new BizException("基线版本不存在"));
        MinorVisit visit = lockVisit(id);
        String operator = requireName(req.operator(), "教练");
        if (!MinorVisit.TRAINING.equals(visit.getStatus())) {
            throw new BizException("交接单 " + visit.getVisitNo() + " 当前为「"
                    + RangeDict.visitStatusName(visit.getStatus()) + "」，训练资源放行只在训练中阶段进行");
        }
        if (visit.isBaselineFrozen()) {
            log(visit, "BLOCK", visit.getStatus(), operator, "COACH",
                    "训练放行被拦截：授权基线已漂移（" + safe(visit.getFreezeReason())
                            + "），须工作人员按新基线重新核对后才能放行");
            throw new com.archery.range.common.GuardianBlockedException("授权基线已变化并冻结："
                    + safe(visit.getFreezeReason())
                    + "。未开始的课程 / 箭道 / 器材动作已冻结，请先在监护授权台按新基线重新核对");
        }
        try {
            assertCurrentEffective(chain, version);
        } catch (BizException e) {
            // 放行时才发现基线失效（理论上冻结联动已置位，双保险）：立即冻结并拦截
            freezeOnTheSpot(visit, e.getMessage());
            log(visit, "BLOCK", visit.getStatus(), operator, "COACH",
                    "训练放行被拦截：" + e.getMessage());
            throw new com.archery.range.common.GuardianBlockedException(e.getMessage());
        }

        Set<Long> courseFilter = new LinkedHashSet<>(distinct(req.courseIds()));
        Set<Long> laneFilter = new LinkedHashSet<>(distinct(req.laneIds()));
        Set<Long> equipFilter = new LinkedHashSet<>(distinct(req.equipmentIds()));

        List<MinorVisitResource> all = resourceRepository.findByVisitIdOrderByIdAsc(id);
        List<MinorVisitResource> targets = all.stream()
                // 已放行是历史事实不再参与；冻结项必须先走重新核对，不会在放行请求里被直接置为已放行
                .filter(r -> MinorVisitResource.PENDING.equals(r.getStatus()))
                .filter(r -> switch (r.getResourceType()) {
                    case MinorVisitResource.COURSE -> courseFilter.isEmpty() || courseFilter.contains(r.getResourceId());
                    case MinorVisitResource.LANE -> laneFilter.isEmpty() || laneFilter.contains(r.getResourceId());
                    default -> equipFilter.isEmpty() || equipFilter.contains(r.getResourceId());
                })
                .toList();
        if (targets.isEmpty()) {
            throw new BizException("没有待放行的资源（可能已全部放行，请勿重复提交；冻结项须先按新基线重新核对）");
        }

        // 整组校验：任一不覆盖 → 整体拒绝，台账不产生 REJECTED 半成品，仅轨迹登记拦截环节与原因。
        Set<Long> allowedCourses = courseScopeRepository.findByVersionId(version.getId()).stream()
                .map(GuardianConsentCourse::getCourseId).collect(Collectors.toSet());
        Set<Long> allowedLanes = laneScopeRepository.findByVersionId(version.getId()).stream()
                .map(GuardianConsentLane::getLaneId).collect(Collectors.toSet());
        Set<Long> allowedEquips = equipScopeRepository.findByVersionId(version.getId()).stream()
                .map(GuardianConsentEquipment::getEquipmentId).collect(Collectors.toSet());

        List<String> blocks = new ArrayList<>();
        for (MinorVisitResource r : targets) {
            boolean covered = switch (r.getResourceType()) {
                case MinorVisitResource.COURSE -> allowedCourses.contains(r.getResourceId());
                case MinorVisitResource.LANE -> allowedLanes.contains(r.getResourceId());
                default -> allowedEquips.contains(r.getResourceId());
            };
            if (!covered) {
                blocks.add(RangeDict.visitResourceTypeName(r.getResourceType()) + " " + r.getResourceLabel());
            }
        }
        if (!blocks.isEmpty()) {
            String reason = "以下资源不在授权 v" + version.getVersionNo() + " 范围内，整组放行被拦截："
                    + String.join("、", blocks)
                    + "；本次没有任何资源被放行，请调整授权范围（新版本重新确认）或调整本次训练内容";
            log(visit, "BLOCK", visit.getStatus(), operator, "COACH", reason);
            throw new com.archery.range.common.GuardianBlockedException(reason);
        }

        // 全部覆盖：targets 已只含 PENDING（FROZEN 在上面被排除且整体冻结提前拦截），原子置为 RELEASED
        LocalDateTime now = LocalDateTime.now();
        for (MinorVisitResource r : targets) {
            r.setStatus(MinorVisitResource.RELEASED);
            r.setReleaseOperator(operator);
            r.setReleasedAt(now);
            resourceRepository.save(r);
        }
        visit.setStartOperator(operator);
        visit.setStartedAt(now);
        visitRepository.save(visit);
        log(visit, "RELEASE", visit.getStatus(), operator, "COACH",
                "训练开始前整组校验通过（依据授权 v" + version.getVersionNo() + "）："
                        + targets.stream().map(r -> RangeDict.visitResourceTypeName(r.getResourceType())
                                + " " + r.getResourceLabel()).collect(Collectors.joining("、"))
                        + " 全部在授权范围内，予以放行");
        return toView(visit);
    }

    // ---------------- 基线冻结后的重新核对：钉到当前新版本 ----------------

    @Transactional
    public GuardianDtos.VisitView recheck(Long id, GuardianDtos.RecheckReq req) {
        MinorVisit read = requireVisit(id);
        // 加锁顺序：授权链 → 当前版本 → 交接单
        GuardianConsent chain = consentRepository.findByIdForUpdate(read.getConsentId())
                .orElseThrow(() -> new BizException("授权链不存在"));
        if (chain.getCurrentVersionId() == null) {
            throw new BizException("授权链当前没有可核对的版本（新版本可能仍在草稿 / 待确认），"
                    + "请待监护人确认后再核对；已开始训练须按异常处置至安全交还");
        }
        GuardianConsentVersion latest = versionRepository.findByIdForUpdate(chain.getCurrentVersionId())
                .orElseThrow(() -> new BizException("当前版本不存在"));
        MinorVisit visit = lockVisit(id);
        String operator = requireName(req.operator(), "工作人员");
        if (!visit.isBaselineFrozen()) {
            throw new BizException("交接单 " + visit.getVisitNo() + " 的授权基线未发生漂移，无需重新核对");
        }
        if (MinorVisit.PENDING_PICKUP.equals(visit.getStatus()) || MinorVisit.CANCELED.equals(visit.getStatus())
                || MinorVisit.RETURNED.equals(visit.getStatus())) {
            throw new BizException("交接单当前为「" + RangeDict.visitStatusName(visit.getStatus())
                    + "」，不能重新核对");
        }
        assertCurrentEffective(chain, latest);

        // 新基线下重新比对尚未放行（冻结）的资源：仍作为整体，任一不覆盖则不切换基线
        List<MinorVisitResource> frozen = resourceRepository.findByVisitIdOrderByIdAsc(id).stream()
                .filter(r -> MinorVisitResource.FROZEN.equals(r.getStatus()) || MinorVisitResource.PENDING.equals(r.getStatus()))
                .toList();
        Set<Long> allowedCourses = courseScopeRepository.findByVersionId(latest.getId()).stream()
                .map(GuardianConsentCourse::getCourseId).collect(Collectors.toSet());
        Set<Long> allowedLanes = laneScopeRepository.findByVersionId(latest.getId()).stream()
                .map(GuardianConsentLane::getLaneId).collect(Collectors.toSet());
        Set<Long> allowedEquips = equipScopeRepository.findByVersionId(latest.getId()).stream()
                .map(GuardianConsentEquipment::getEquipmentId).collect(Collectors.toSet());
        List<String> stillBlocked = new ArrayList<>();
        for (MinorVisitResource r : frozen) {
            boolean covered = switch (r.getResourceType()) {
                case MinorVisitResource.COURSE -> allowedCourses.contains(r.getResourceId());
                case MinorVisitResource.LANE -> allowedLanes.contains(r.getResourceId());
                default -> allowedEquips.contains(r.getResourceId());
            };
            if (!covered) {
                stillBlocked.add(RangeDict.visitResourceTypeName(r.getResourceType()) + " " + r.getResourceLabel());
            }
        }
        if (!stillBlocked.isEmpty()) {
            log(visit, "BLOCK", visit.getStatus(), operator, "MANAGER",
                    "重新核对未通过：新基线 v" + latest.getVersionNo() + " 仍不覆盖 "
                            + String.join("、", stillBlocked) + "，维持冻结");
            throw new BizException("按新基线 v" + latest.getVersionNo() + " 重新核对未通过："
                    + String.join("、", stillBlocked)
                    + " 仍不在授权范围内。请调整训练内容（移除相关资源后重试）或由值班经理按异常处置安全交还");
        }

        // 重新核对通过：基线钉到新版本（接管事实仍保留：pickup_* 字段不动），冻结项回到待放行
        Long oldVersionId = visit.getBaselineVersionId();
        visit.setBaselineVersionId(latest.getId());
        visit.setBaselineFingerprint(latest.getContentFingerprint());
        visit.setBaselineFrozen(false);
        visit.setFreezeReason(null);
        visit.setFrozenAt(null);
        visitRepository.save(visit);
        LocalDateTime now = LocalDateTime.now();
        for (MinorVisitResource r : frozen) {
            r.setStatus(MinorVisitResource.PENDING);
            resourceRepository.save(r);
        }
        log(visit, "RECHECK", visit.getStatus(), operator, "MANAGER",
                "工作人员按新基线重新核对通过：v" + versionNoOf(oldVersionId) + " → v" + latest.getVersionNo()
                        + "；已发生的接管 / 已放行资源事实保留，冻结资源恢复为待放行，"
                        + "训练放行仍须按 v" + latest.getVersionNo() + " 整组校验");
        return toView(visit);
    }

    /** 工作人员调整本次训练内容：把新基线下不再覆盖、尚未放行的资源从台账移除（整组移除，留轨迹） */
    @Transactional
    public GuardianDtos.VisitView trimResources(Long id, GuardianDtos.ReleaseReq req) {
        MinorVisit visit = lockVisit(id);
        String operator = requireName(req.operator(), "工作人员");
        if (!MinorVisit.TRAINING.equals(visit.getStatus())) {
            throw new BizException("只有训练中阶段可以调整本次训练资源");
        }
        Set<Long> courseFilter = new LinkedHashSet<>(distinct(req.courseIds()));
        Set<Long> laneFilter = new LinkedHashSet<>(distinct(req.laneIds()));
        Set<Long> equipFilter = new LinkedHashSet<>(distinct(req.equipmentIds()));
        if (courseFilter.isEmpty() && laneFilter.isEmpty() && equipFilter.isEmpty()) {
            throw new BizException("请选择要移除的资源（移除后整组重新核对）");
        }
        List<MinorVisitResource> removable = resourceRepository.findByVisitIdOrderByIdAsc(id).stream()
                .filter(r -> !MinorVisitResource.RELEASED.equals(r.getStatus()))
                .filter(r -> switch (r.getResourceType()) {
                    case MinorVisitResource.COURSE -> courseFilter.contains(r.getResourceId());
                    case MinorVisitResource.LANE -> laneFilter.contains(r.getResourceId());
                    default -> equipFilter.contains(r.getResourceId());
                })
                .toList();
        if (removable.isEmpty()) {
            throw new BizException("所选资源均已放行或不在本交接单内，不能移除（已放行事实不可改写）");
        }
        List<String> labels = removable.stream()
                .map(r -> RangeDict.visitResourceTypeName(r.getResourceType()) + " " + r.getResourceLabel())
                .toList();
        resourceRepository.deleteAll(removable);
        log(visit, "EXCEPTION", visit.getStatus(), operator, "MANAGER",
                "按新基线移除未放行资源（已放行资源事实保留）：" + String.join("、", labels));
        return toView(visit);
    }

    // ---------------- 训练结束待交还 / 安全交还 ----------------

    @Transactional
    public GuardianDtos.VisitView requestReturn(Long id, GuardianDtos.OperatorReq req) {
        MinorVisit visit = lockVisit(id);
        String operator = requireName(req.operator(), "前台");
        if (!MinorVisit.TRAINING.equals(visit.getStatus())) {
            throw new BizException("交接单当前为「" + RangeDict.visitStatusName(visit.getStatus())
                    + "」，只有训练中可以结束训练等待交还");
        }
        visit.setStatus(MinorVisit.PENDING_RETURN);
        visitRepository.save(visit);
        log(visit, "WAIT_RETURN", MinorVisit.TRAINING, operator, "FRONT",
                "训练结束，进入待交还，等待合格接管人到场完成交还确认");
        return toView(visit);
    }

    @Transactional
    public GuardianDtos.VisitView completeReturn(Long id, GuardianDtos.ReturnReq req) {
        MinorVisit visit = lockVisit(id);
        String operator = requireName(req.operator(), "前台");
        if (MinorVisit.RETURNED.equals(visit.getStatus())) {
            throw new BizException("交接单 " + visit.getVisitNo() + " 已完成交还，请勿重复确认（依据基线 v"
                    + versionNoOf(visit.getBaselineVersionId()) + "）");
        }
        if (MinorVisit.CANCELED.equals(visit.getStatus())) {
            throw new BizException("交接单已取消，不能交还");
        }
        if (MinorVisit.PENDING_PICKUP.equals(visit.getStatus())) {
            throw new BizException("尚未完成接管，不能交还；未实际接管的单据请按取消处理");
        }
        GuardianDtos.PersonReq person = req.person();
        if (person == null) {
            throw new BizException("请核验交还人身份信息");
        }
        String name = requireName(person.name(), "交还人");
        String idNo = requireName(person.idNo(), "交还人证件标识");
        String phone = requirePhone(person.phone(), "交还人手机号");
        if (!RangeDict.isValidGuardianRelation(person.relation())) {
            throw new BizException("交还人关系只能是：父亲 / 母亲 / 祖父母 / 其他法定监护人");
        }

        // 合格接管人：在「本次交接基线版本」的接管人名单内（即使授权之后换版，也以重新核对后的基线为准）
        GuardianConsentVersion baseline = versionRepository.findById(visit.getBaselineVersionId())
                .orElseThrow(() -> new BizException("基线版本不存在"));
        boolean qualified = pickupRepository.findByVersionId(baseline.getId()).stream()
                .anyMatch(p -> p.getPersonName().equals(name) && p.getIdNo().equals(idNo));

        boolean override = Boolean.TRUE.equals(req.managerOverride());
        String managerName = req.managerName() == null ? "" : req.managerName().trim();
        String overrideReason = req.overrideReason() == null ? "" : req.overrideReason().trim();
        if (!qualified) {
            if (!override) {
                log(visit, "BLOCK", visit.getStatus(), operator, "FRONT",
                        "交还被拦截：交还人 " + name + "（证件 " + idNo + "）不在基线 v"
                                + baseline.getVersionNo() + " 接管人名单内，未完成交还（记录保持"
                                + RangeDict.visitStatusName(visit.getStatus()) + "，不能静默关闭）");
                throw new BizException("交还人 " + name + "（证件标识 " + idNo + "）不在授权 v"
                        + baseline.getVersionNo() + " 接管人名单内，不能完成交还。"
                        + "请名单内接管人到场；确需其他人接走时，必须由值班经理异常处置并填写原因");
            }
            if (managerName.isEmpty()) {
                throw new BizException("异常处置必须填写值班经理姓名");
            }
            if (overrideReason.isEmpty()) {
                throw new BizException("非名单接管人接走，值班经理必须填写异常处置原因");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        visit.setReturnPersonName(name);
        visit.setReturnPersonIdno(idNo);
        visit.setReturnPersonPhone(phone);
        visit.setReturnPersonRelation(person.relation());
        visit.setQualifiedPickup(qualified);
        visit.setManagerOverride(!qualified); // qualified 时强制 false，避免误传
        if (!qualified) {
            visit.setOverrideManager(managerName);
            visit.setOverrideReason(overrideReason);
        }
        visit.setReturnOperator(operator);
        visit.setReturnedAt(now);
        visit.setStatus(MinorVisit.RETURNED);
        visitRepository.save(visit);

        if (qualified) {
            log(visit, "RETURN", visit.getStatus(), operator, "FRONT",
                    "安全交还：交还人 " + name + "（证件 " + idNo + "）在基线 v" + baseline.getVersionNo()
                            + " 接管人名单内，交接完成"
                            + (visit.isBaselineFrozen() ? "（交还时基线处于冻结状态，按异常处置流程闭环）" : ""));
        } else {
            log(visit, "EXCEPTION", visit.getStatus(), managerName, "MANAGER",
                    "异常处置：交还人 " + name + "（证件 " + idNo + "）不在基线 v" + baseline.getVersionNo()
                            + " 接管人名单内，值班经理 " + managerName + " 确认放行：" + overrideReason);
            log(visit, "RETURN", visit.getStatus(), operator, "FRONT",
                    "凭值班经理异常处置结论完成安全交还，交接单闭环");
        }
        return toView(visit);
    }

    // ---------------- 取消（仅待接管，必填原因） ----------------

    @Transactional
    public GuardianDtos.VisitView cancel(Long id, GuardianDtos.CancelReq req) {
        MinorVisit visit = lockVisit(id);
        String operator = requireName(req.operator(), "前台");
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            throw new BizException("取消交接必须填写原因");
        }
        if (!MinorVisit.PENDING_PICKUP.equals(visit.getStatus())) {
            throw new BizException("已接管的交接不能取消，必须完成安全交还；当前为「"
                    + RangeDict.visitStatusName(visit.getStatus()) + "」");
        }
        visit.setStatus(MinorVisit.CANCELED);
        visit.setCancelOperator(operator);
        visit.setCanceledAt(LocalDateTime.now());
        visit.setCancelReason(reason);
        visitRepository.save(visit);
        log(visit, "CANCEL", MinorVisit.PENDING_PICKUP, operator, "FRONT", "取消交接：" + reason);
        return toView(visit);
    }

    // ---------------- 业务入口门禁（开台 / 租借 / 报名 调用） ----------------

    /** 未成年会员当前有效授权（无则抛业务异常，供课程报名等前置门禁） */
    @Transactional(readOnly = true)
    public GuardianConsentVersion consentEffectiveForMember(Member member) {
        return consentService.requireEffectiveVersion(member);
    }

    /** 课程是否被某授权版本覆盖（供课程报名门禁） */
    @Transactional(readOnly = true)
    public boolean isCourseCovered(GuardianConsentVersion version, Long courseId) {
        return courseScopeRepository.findByVersionId(version.getId()).stream()
                .anyMatch(row -> row.getCourseId().equals(courseId));
    }

    /**
     * 未成年会员资源动作门禁。
     * @param member 会员（调用方已确认 minorFlag=true）
     * @param type COURSE / LANE / EQUIPMENT
     * @param resourceId 资源 id
     * @param actionName 业务动作中文名（拦截提示用，如「开台」「租借器材」）
     */
    @Transactional(readOnly = true)
    public void guard(Member member, String type, Long resourceId, String actionName) {
        // 成年会员流程完全不变（双保险：调用方均已按 minorFlag 分支）
        if (member == null || !member.isMinorFlag()) {
            return;
        }
        GuardianConsentVersion effective = consentService.findEffectiveVersion(member.getId());
        if (effective == null) {
            throw new BizException("未成年会员 [" + member.getName() + "] 当前没有有效监护授权（"
                    + reasonIfKnown(member) + "），" + actionName + " 被禁止；请先完成监护授权与现场接管");
        }
        boolean covered = switch (type) {
            case MinorVisitResource.COURSE -> courseScopeRepository.findByVersionId(effective.getId()).stream()
                    .anyMatch(row -> row.getCourseId().equals(resourceId));
            case MinorVisitResource.LANE -> laneScopeRepository.findByVersionId(effective.getId()).stream()
                    .anyMatch(row -> row.getLaneId().equals(resourceId));
            case MinorVisitResource.EQUIPMENT -> equipScopeRepository.findByVersionId(effective.getId()).stream()
                    .anyMatch(row -> row.getEquipmentId().equals(resourceId));
            default -> false;
        };
        if (!covered) {
            throw new BizException("授权 v" + effective.getVersionNo() + " 的"
                    + RangeDict.visitResourceTypeName(type) + "范围不包含本次" + actionName
                    + "所选资源，已被监护授权台拦截；请监护人调整范围（新版本重新确认）");
        }
        // 现场交接：训练中的在途交接且未冻结才允许现场资源动作
        List<MinorVisit> open = visitRepository.findByMemberIdAndStatusInOrderByIdDesc(
                member.getId(), List.of(MinorVisit.TRAINING));
        if (open.isEmpty()) {
            throw new BizException("未成年会员 [" + member.getName() + "] 尚未完成现场接管 / 训练签到，"
                    + actionName + " 被禁止；请先在监护授权台完成接管与训练放行");
        }
        MinorVisit visit = open.get(0);
        if (visit.isBaselineFrozen()) {
            throw new BizException("会员 [" + member.getName() + "] 的到馆交接 " + visit.getVisitNo()
                    + " 授权基线已漂移并冻结（" + safe(visit.getFreezeReason())
                    + "），" + actionName + " 被拦截；请按新基线重新核对后再操作");
        }
        if (!visit.getBaselineVersionId().equals(effective.getId())) {
            throw new BizException("到馆交接 " + visit.getVisitNo() + " 钉住的授权基线 v"
                    + versionNoOf(visit.getBaselineVersionId()) + " 与当前有效版本 v"
                    + effective.getVersionNo() + " 不一致，" + actionName
                    + " 被拦截；请工作人员在监护授权台完成重新核对");
        }
        // 该资源必须在本交接台账内且已放行
        boolean released = resourceRepository.findByVisitIdOrderByIdAsc(visit.getId()).stream()
                .anyMatch(r -> r.getResourceType().equals(type)
                        && r.getResourceId().equals(resourceId)
                        && MinorVisitResource.RELEASED.equals(r.getStatus()));
        if (!released) {
            throw new BizException(RangeDict.visitResourceTypeName(type) + " 未在交接 " + visit.getVisitNo()
                    + " 的训练放行范围内，" + actionName + " 被拦截；请由教练在训练开始前完成整组校验放行");
        }
    }

    // ---------------- 内部工具 ----------------

    private String reasonIfKnown(Member member) {
        List<GuardianConsent> chains = consentRepository.findByMemberIdOrderByIdDesc(member.getId());
        if (chains.isEmpty()) {
            return "尚未建档";
        }
        GuardianConsent chain = chains.get(0);
        return "授权状态「" + RangeDict.consentStatusName(chain.getStatus()) + "」";
    }

    /** 放行时发现基线失效的兜底冻结（授权侧联动的双保险） */
    private void freezeOnTheSpot(MinorVisit visit, String reason) {
        if (visit.isBaselineFrozen()) {
            return;
        }
        visit.setBaselineFrozen(true);
        visit.setFreezeReason(reason);
        visit.setFrozenAt(LocalDateTime.now());
        visitRepository.save(visit);
        for (MinorVisitResource r : resourceRepository.findByVisitIdOrderByIdAsc(visit.getId())) {
            if (MinorVisitResource.PENDING.equals(r.getStatus())) {
                r.setStatus(MinorVisitResource.FROZEN);
                resourceRepository.save(r);
            }
        }
        log(visit, "FREEZE", visit.getStatus(), "授权系统", "SYSTEM",
                "放行校验时发现授权基线失效，立即冻结未开始动作：" + reason);
    }

    private void assertCurrentEffective(GuardianConsent chain, GuardianConsentVersion version) {
        if (!chain.getCurrentVersionId().equals(version.getId())) {
            throw new BizException("授权当前版本为 v" + versionNoOf(chain.getCurrentVersionId())
                    + "，本单依据的 v" + version.getVersionNo() + " 已不是当前版本");
        }
        if (GuardianConsent.REVOKED.equals(chain.getStatus())) {
            throw new BizException("授权已被监护人 / 值班经理撤销");
        }
        if (GuardianConsent.SUSPENDED.equals(chain.getStatus())) {
            throw new BizException("授权处于暂停状态");
        }
        if (!GuardianConsent.VALID.equals(chain.getStatus())
                || !GuardianConsentVersion.VALID.equals(version.getStatus())) {
            throw new BizException("授权尚未完成监护人确认（当前「"
                    + RangeDict.consentVersionStatusName(version.getStatus()) + "」）");
        }
        var today = java.time.LocalDate.now();
        if (today.isBefore(version.getEffectiveFrom())) {
            throw new BizException("授权自 " + version.getEffectiveFrom() + " 才生效");
        }
        if (today.isAfter(version.getEffectiveUntil())) {
            throw new BizException("授权已于 " + version.getEffectiveUntil() + " 到期");
        }
    }

    private void saveResource(MinorVisit visit, String type, Long resourceId, String label, LocalDateTime now) {
        MinorVisitResource row = new MinorVisitResource();
        row.setVisit(visit);
        row.setResourceType(type);
        row.setResourceId(resourceId);
        row.setResourceLabel(label);
        row.setStatus(MinorVisitResource.PENDING);
        row.setCreatedAt(now);
        resourceRepository.save(row);
    }

    private MinorVisit lockVisit(Long id) {
        return visitRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("到馆交接单不存在：id=" + id));
    }

    private MinorVisit requireVisit(Long id) {
        return visitRepository.findById(id)
                .orElseThrow(() -> new BizException("到馆交接单不存在：id=" + id));
    }

    private void log(MinorVisit visit, String action, String stage,
            String operator, String role, String detail) {
        MinorVisitLog entry = new MinorVisitLog();
        entry.setVisit(visit);
        entry.setAction(action);
        entry.setStage(stage);
        entry.setOperator(operator);
        entry.setRole(role);
        entry.setDetail(detail);
        entry.setCreatedAt(LocalDateTime.now());
        logRepository.save(entry);
    }

    private String nextVisitNo() {
        String no = "V" + LocalDateTime.now().format(NO_STAMP);
        int seq = 1;
        while (visitRepository.existsByVisitNo(no)) {
            no = "V" + LocalDateTime.now().format(NO_STAMP) + seq;
            seq += 1;
        }
        return no;
    }

    private List<Long> distinct(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(ids.stream().filter(java.util.Objects::nonNull).toList()));
    }

    private String requireName(String value, String who) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            throw new BizException("请填写" + who + "姓名");
        }
        if (v.length() > 32) {
            throw new BizException(who + "姓名过长");
        }
        return v;
    }

    private String requirePhone(String value, String who) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty() || !v.matches("1[3-9]\\d{9}")) {
            throw new BizException(who + "格式不正确（需为 11 位手机号）");
        }
        return v;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private Integer versionNoOf(Long versionId) {
        if (versionId == null) {
            return null;
        }
        return versionRepository.findById(versionId).map(GuardianConsentVersion::getVersionNo).orElse(null);
    }

    // ---------------- 出参 ----------------

    public GuardianDtos.VisitSummary toSummary(MinorVisit visit) {
        return new GuardianDtos.VisitSummary(
                visit.getId(),
                visit.getVisitNo(),
                visit.getMember().getId(),
                visit.getMember().getName(),
                visit.getMember().getCardNo(),
                visit.getStatus(),
                RangeDict.visitStatusName(visit.getStatus()),
                visit.getConsentId(),
                consentRepository.findById(visit.getConsentId()).map(GuardianConsent::getConsentNo).orElse(""),
                visit.getBaselineVersionId(),
                versionNoOf(visit.getBaselineVersionId()),
                visit.isBaselineFrozen(),
                visit.getFreezeReason(),
                visit.getPickupPersonName(),
                visit.getPickupPersonIdno(),
                visit.getPickupAt(),
                visit.getStartedAt(),
                visit.getReturnPersonName(),
                visit.getQualifiedPickup(),
                visit.isManagerOverride(),
                visit.getReturnedAt(),
                visit.getCreatedAt());
    }

    private GuardianDtos.VisitView toView(MinorVisit visit) {
        GuardianConsentVersion baseline = versionRepository.findById(visit.getBaselineVersionId()).orElse(null);
        GuardianDtos.VersionView baselineView = baseline == null ? null : consentService.toVersionView(baseline);
        GuardianConsent chain = consentRepository.findById(visit.getConsentId()).orElse(null);
        GuardianConsentVersion current = chain == null || chain.getCurrentVersionId() == null ? null
                : versionRepository.findById(chain.getCurrentVersionId()).orElse(null);

        List<GuardianDtos.VisitResourceView> resources =
                resourceRepository.findByVisitIdOrderByIdAsc(visit.getId()).stream()
                        .map(r -> new GuardianDtos.VisitResourceView(
                                r.getId(),
                                r.getResourceType(),
                                RangeDict.visitResourceTypeName(r.getResourceType()),
                                r.getResourceId(),
                                r.getResourceLabel(),
                                r.getStatus(),
                                RangeDict.visitResourceStatusName(r.getStatus()),
                                r.getReleaseOperator(),
                                r.getReleasedAt(),
                                r.getRejectReason()))
                        .toList();
        List<GuardianDtos.VisitLogView> logs = logRepository.findByVisitIdOrderByIdAsc(visit.getId()).stream()
                .map(l -> new GuardianDtos.VisitLogView(
                        l.getId(),
                        l.getAction(),
                        RangeDict.visitActionName(l.getAction()),
                        l.getStage(),
                        l.getStage() == null ? "" : RangeDict.visitStatusName(l.getStage()),
                        l.getOperator(),
                        l.getRole(),
                        RangeDict.guardianRoleName(l.getRole()),
                        l.getDetail(),
                        l.getCreatedAt()))
                .toList();
        return new GuardianDtos.VisitView(
                toSummary(visit),
                baselineView,
                chain == null ? "" : chain.getStatus(),
                chain == null ? "" : RangeDict.consentStatusName(chain.getStatus()),
                current == null ? null : current.getId(),
                current == null ? null : current.getVersionNo(),
                resources,
                logs);
    }
}
