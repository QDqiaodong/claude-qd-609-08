package com.archery.range.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import com.archery.range.domain.GuardianConsentLog;
import com.archery.range.domain.GuardianConsentPickup;
import com.archery.range.domain.GuardianConsentVersion;
import com.archery.range.domain.Lane;
import com.archery.range.domain.Member;
import com.archery.range.domain.MinorVisit;
import com.archery.range.domain.MinorVisitResource;
import com.archery.range.domain.RangeDict;
import com.archery.range.dto.GuardianDtos;
import com.archery.range.repository.CourseRepository;
import com.archery.range.repository.EquipmentRepository;
import com.archery.range.repository.GuardianConsentCourseRepository;
import com.archery.range.repository.GuardianConsentEquipmentRepository;
import com.archery.range.repository.GuardianConsentLaneRepository;
import com.archery.range.repository.GuardianConsentLogRepository;
import com.archery.range.repository.GuardianConsentPickupRepository;
import com.archery.range.repository.GuardianConsentRepository;
import com.archery.range.repository.GuardianConsentVersionRepository;
import com.archery.range.repository.LaneRepository;
import com.archery.range.repository.MemberRepository;
import com.archery.range.repository.MinorVisitRepository;
import com.archery.range.repository.MinorVisitResourceRepository;

/**
 * 监护授权链服务（未成年会员监护授权台的授权侧）。
 *
 * 关键设计：
 * 1. 版本不可变 —— 版本行 + 四张范围表一次性写入后再无 UPDATE 内容的入口；
 *    范围（课程 / 箭道 / 器材）、生效日与终止日、接管人名单任一变化只能发布新版本重新确认，
 *    内容以 SHA-256 指纹固化，确认 / 接管时核对，杜绝「同版本号不同内容」。
 * 2. 状态机集中串行 —— 链行 FOR UPDATE 串行所有版本动作；监护人确认是版本生效的唯一入口；
 *    新 VALID 版本产生时，旧 VALID 版本与链指针在同一事务内切到 SUPERSEDED，
 *    不会出现两个同时放行的版本，也不会出现无有效版本的空窗。
 * 3. 基线漂移联动 —— 接管后发生换版 / 暂停 / 撤销时，本服务在同一事务内冻结钉住旧版本的
 *    在途交接单（未开始的课程 / 箭道 / 器材动作冻结），已发生的接管 / 训练事实一律不改写。
 * 4. 一组范围整体处理 —— 建版本时课程 / 箭道 / 器材作为一个整体校验，任一资源不存在直接整体失败，
 *    绝不留下半完成的版本。
 */
@Service
public class GuardianConsentService {

    private static final DateTimeFormatter NO_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final GuardianConsentRepository consentRepository;
    private final GuardianConsentVersionRepository versionRepository;
    private final GuardianConsentCourseRepository courseScopeRepository;
    private final GuardianConsentLaneRepository laneScopeRepository;
    private final GuardianConsentEquipmentRepository equipScopeRepository;
    private final GuardianConsentPickupRepository pickupRepository;
    private final GuardianConsentLogRepository logRepository;
    private final MemberRepository memberRepository;
    private final CourseRepository courseRepository;
    private final LaneRepository laneRepository;
    private final EquipmentRepository equipmentRepository;
    private final MinorVisitRepository visitRepository;
    private final MinorVisitResourceRepository visitResourceRepository;
    private final com.archery.range.repository.MinorVisitLogRepository visitLogRepository;

    public GuardianConsentService(GuardianConsentRepository consentRepository,
            GuardianConsentVersionRepository versionRepository,
            GuardianConsentCourseRepository courseScopeRepository,
            GuardianConsentLaneRepository laneScopeRepository,
            GuardianConsentEquipmentRepository equipScopeRepository,
            GuardianConsentPickupRepository pickupRepository,
            GuardianConsentLogRepository logRepository,
            MemberRepository memberRepository,
            CourseRepository courseRepository,
            LaneRepository laneRepository,
            EquipmentRepository equipmentRepository,
            MinorVisitRepository visitRepository,
            MinorVisitResourceRepository visitResourceRepository,
            com.archery.range.repository.MinorVisitLogRepository visitLogRepository) {
        this.consentRepository = consentRepository;
        this.versionRepository = versionRepository;
        this.courseScopeRepository = courseScopeRepository;
        this.laneScopeRepository = laneScopeRepository;
        this.equipScopeRepository = equipScopeRepository;
        this.pickupRepository = pickupRepository;
        this.logRepository = logRepository;
        this.memberRepository = memberRepository;
        this.courseRepository = courseRepository;
        this.laneRepository = laneRepository;
        this.equipmentRepository = equipmentRepository;
        this.visitRepository = visitRepository;
        this.visitResourceRepository = visitResourceRepository;
        this.visitLogRepository = visitLogRepository;
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public List<GuardianDtos.ConsentSummary> list(Long memberId) {
        List<GuardianConsent> chains = memberId == null
                ? consentRepository.findAllByOrderByIdDesc()
                : consentRepository.findByMemberIdOrderByIdDesc(memberId);
        return chains.stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public GuardianDtos.ConsentView detail(Long id) {
        GuardianConsent consent = requireConsent(id);
        List<GuardianDtos.VersionView> versions = versionRepository
                .findByConsentIdOrderByVersionNoDesc(id).stream()
                .map(this::toVersionView)
                .toList();
        List<GuardianDtos.ConsentLogView> logs = logRepository.findByConsentIdOrderByIdAsc(id).stream()
                .map(this::toLogView)
                .toList();
        return new GuardianDtos.ConsentView(toSummary(consent), versions, logs);
    }

    // ---------------- 建立授权链（含首版草稿，整体写入） ----------------

    @Transactional
    public GuardianDtos.ConsentView create(GuardianDtos.CreateConsentReq req) {
        String operator = requireName(req.operator(), "前台");
        Member member = memberRepository.findById(req.memberId())
                .orElseThrow(() -> new BizException("会员不存在：id=" + req.memberId()));
        if (!member.isMinorFlag()) {
            throw new BizException("会员 [" + member.getName() + "] 不是未成年会员，成年会员流程不需要监护授权");
        }
        if (Member.STATUS_MERGED.equals(member.getStatus())) {
            throw new BizException("会员卡 [" + member.getCardNo() + "] 已合并封存，不能建立监护授权");
        }
        if (!consentRepository.findByMemberIdOrderByIdDesc(member.getId()).isEmpty()) {
            throw new BizException("会员 [" + member.getName() + "] 已存在监护授权链，请在原链上发布新版本，不要重复建档");
        }
        String guardianName = requireName(req.guardianName(), "监护人");
        String guardianPhone = requirePhone(req.guardianPhone(), "监护人手机号");
        String emergencyName = requireName(req.emergencyContactName(), "紧急联系人");
        String emergencyPhone = requirePhone(req.emergencyContactPhone(), "紧急联系人电话");
        if (!RangeDict.isValidGuardianRelation(req.guardianRelation())) {
            throw new BizException("监护人身份关系只能是：父亲 / 母亲 / 其他法定监护人");
        }
        GuardianDtos.VersionContent content = validateContent(req.version());

        LocalDateTime now = LocalDateTime.now();
        GuardianConsent consent = new GuardianConsent();
        consent.setConsentNo(nextConsentNo());
        consent.setMember(member);
        consent.setGuardianName(guardianName);
        consent.setGuardianPhone(guardianPhone);
        consent.setGuardianRelation(req.guardianRelation());
        consent.setStatus(GuardianConsent.DRAFT);
        consent.setEmergencyContactName(emergencyName);
        consent.setEmergencyContactPhone(emergencyPhone);
        consent.setRemark(trimToNull(req.chainRemark()));
        consent.setCreatedBy(operator);
        consent.setCreatedAt(now);
        consent.setUpdatedAt(now);
        consentRepository.save(consent);

        GuardianConsentVersion version = writeVersion(consent, 1, content, operator, now);
        consent.setCurrentVersionId(version.getId());
        consentRepository.save(consent);

        logConsent(consent, version.getId(), 1, "CREATE", null, GuardianConsent.DRAFT, operator, "FRONT",
                "建立监护授权链：" + member.getName() + "；监护人 " + guardianName
                        + RangeDict.guardianRelationName(req.guardianRelation()) + "；v1 草稿（"
                        + content.courseIds().size() + " 门课程 / " + content.laneIds().size() + " 条箭道 / "
                        + content.equipmentIds().size() + " 件器材 / " + content.pickups().size() + " 名接管人）");
        return detail(consent.getId());
    }

    // ---------------- 修改草稿（只改 DRAFT；提交后要变必须发新版本） ----------------

    @Transactional
    public GuardianDtos.ConsentView updateDraft(Long id, GuardianDtos.UpdateDraftReq req) {
        GuardianConsent consent = lockConsent(id);
        GuardianConsentVersion version = currentDraftVersion(consent);
        String operator = requireName(req.operator(), "前台");
        GuardianDtos.VersionContent content = validateContent(req.version());

        // 草稿版本内容尚未提交确认：直接以「同版本号、新指纹」重写草稿内容。
        // 版本实体本身更新（DRAFT 未成为任何基线），范围行整组删除后按新内容整体重建。
        LocalDateTime now = LocalDateTime.now();
        clearScopeRows(version.getId());
        fillScopeRows(version, content);
        version.setEffectiveFrom(content.effectiveFrom());
        version.setEffectiveUntil(content.effectiveUntil());
        version.setVersionRemark(trimToNull(content.remark()));
        version.setContentFingerprint(fingerprint(1, content));
        versionRepository.save(version);
        consent.setUpdatedAt(now);
        consentRepository.save(consent);
        logConsent(consent, version.getId(), version.getVersionNo(), "CREATE",
                GuardianConsent.DRAFT, GuardianConsent.DRAFT, operator, "FRONT",
                "修改草稿 v" + version.getVersionNo() + " 内容（草稿未确认，不产生新版本）");
        return detail(id);
    }

    // ---------------- 提交监护人确认：DRAFT → PENDING_CONFIRM ----------------

    @Transactional
    public GuardianDtos.ConsentView submit(Long id, GuardianDtos.OperatorReq req) {
        GuardianConsent consent = lockConsent(id);
        GuardianConsentVersion version = currentVersion(consent);
        String operator = requireName(req.operator(), "前台");
        if (!GuardianConsentVersion.DRAFT.equals(version.getStatus())) {
            throw new BizException("版本 v" + version.getVersionNo() + " 当前为「"
                    + RangeDict.consentVersionStatusName(version.getStatus()) + "」，不能提交确认");
        }
        LocalDateTime now = LocalDateTime.now();
        version.setStatus(GuardianConsentVersion.PENDING_CONFIRM);
        versionRepository.save(version);
        consent.setStatus(GuardianConsent.PENDING_CONFIRM);
        consent.setUpdatedAt(now);
        consentRepository.save(consent);
        logConsent(consent, version.getId(), version.getVersionNo(), "SUBMIT",
                GuardianConsent.DRAFT, GuardianConsent.PENDING_CONFIRM, operator, "FRONT",
                "v" + version.getVersionNo() + " 提交监护人现场确认");
        return detail(id);
    }

    // ---------------- 监护人确认 / 拒绝（版本生效唯一入口；并发只落一个结果） ----------------

    @Transactional
    public GuardianDtos.ConsentView confirm(Long id, GuardianDtos.ConfirmReq req) {
        // 加锁顺序固定为 授权链 → 当前版本（与接管 / 放行 / 冻结联动一致，避免交叉等待死锁）
        GuardianConsent consent = lockConsent(id);
        GuardianConsentVersion version = consent.getCurrentVersionId() == null ? null
                : versionRepository.findByIdForUpdate(consent.getCurrentVersionId()).orElse(null);
        if (version == null) {
            throw new BizException("授权链缺少待确认版本，请刷新后重试");
        }
        boolean reject = Boolean.TRUE.equals(req.reject());
        String confirmer = requireName(req.confirmerName(), "确认人");
        if (!RangeDict.isValidGuardianRelation(req.confirmRelation())) {
            throw new BizException("确认人身份关系只能是：父亲 / 母亲 / 其他法定监护人");
        }
        // 过期 / 重复确认：以客户端提交时看到的版本号为依据
        if (req.expectedVersionNo() != null && !req.expectedVersionNo().equals(version.getVersionNo())) {
            throw new BizException("确认依据的是 v" + req.expectedVersionNo()
                    + "，但该授权当前待确认版本已变为 v" + version.getVersionNo()
                    + "（可能已被工作人员更新），请按 v" + version.getVersionNo() + " 重新核对后再确认");
        }
        LocalDateTime now = LocalDateTime.now();

        if (reject) {
            String reason = req.rejectReason() == null ? "" : req.rejectReason().trim();
            if (reason.isEmpty()) {
                throw new BizException("监护人拒绝确认时必须填写原因");
            }
            if (!GuardianConsentVersion.PENDING_CONFIRM.equals(version.getStatus())) {
                throw new BizException("版本 v" + version.getVersionNo() + " 当前为「"
                        + RangeDict.consentVersionStatusName(version.getStatus()) + "」，不能拒绝确认");
            }
            version.setStatus(GuardianConsentVersion.REJECTED);
            version.setRejectedBy(confirmer);
            version.setRejectedAt(now);
            version.setRejectReason(reason);
            versionRepository.save(version);
            consent.setStatus(GuardianConsent.DRAFT);
            consent.setUpdatedAt(now);
            consentRepository.save(consent);
            logConsent(consent, version.getId(), version.getVersionNo(), "REJECT",
                    GuardianConsent.PENDING_CONFIRM, GuardianConsent.DRAFT, confirmer, "GUARDIAN",
                    "监护人拒绝确认 v" + version.getVersionNo() + "：" + reason + "（请按意见发布新版本）");
            return detail(id);
        }

        if (!GuardianConsentVersion.PENDING_CONFIRM.equals(version.getStatus())) {
            // 明确展示所依据的版本，供重复 / 过期确认者判断
            throw new BizException("v" + version.getVersionNo() + " 当前为「"
                    + RangeDict.consentVersionStatusName(version.getStatus())
                    + "」，监护人确认只在待确认状态生效，请勿重复确认");
        }
        if (version.getEffectiveFrom().isBefore(LocalDate.now())) {
            // 生效日不能早于今天（不允许补登记追溯授权）；终止日同理不能早于生效日
            throw new BizException("v" + version.getVersionNo() + " 的生效日 " + version.getEffectiveFrom()
                    + " 已过，不能追溯确认，请修改生效日后发布新版本");
        }
        String note = trimToNull(req.note());

        // 原子切换：新版本 → VALID；旧 VALID 版本（含链状态）→ SUPERSEDED。
        // 链行悲观锁已保证并发确认在此串行，这里仍以状态为条件，重复确认只会有一个结果。
        GuardianConsentVersion previous = null;
        if (consent.getCurrentVersionId() != null
                && !consent.getCurrentVersionId().equals(version.getId())) {
            throw new BizException("授权链当前版本与待确认版本不一致，请刷新后重试");
        }
        // 同链历史中可能存在已被确认过的 VALID 版本（理论上 currentVersion 即它，这里双保险）
        for (GuardianConsentVersion v : versionRepository.findByConsentIdOrderByVersionNoDesc(id)) {
            if (GuardianConsentVersion.VALID.equals(v.getStatus())) {
                previous = v;
                break;
            }
        }

        version.setStatus(GuardianConsentVersion.VALID);
        version.setConfirmedBy(confirmer);
        version.setConfirmedAt(now);
        version.setConfirmRelation(req.confirmRelation());
        version.setConfirmNote(note);
        versionRepository.save(version);

        String beforeChain = consent.getStatus();
        consent.setStatus(GuardianConsent.VALID);
        consent.setCurrentVersionId(version.getId());
        consent.setUpdatedAt(now);
        consentRepository.save(consent);
        logConsent(consent, version.getId(), version.getVersionNo(), "CONFIRM",
                beforeChain, GuardianConsent.VALID, confirmer, "GUARDIAN",
                "监护人 " + confirmer + "（" + RangeDict.guardianRelationName(req.confirmRelation())
                        + "）现场确认 v" + version.getVersionNo() + "，有效期 "
                        + version.getEffectiveFrom() + " 至 " + version.getEffectiveUntil()
                        + (note == null ? "" : "；备注：" + note));

        if (previous != null) {
            previous.setStatus(GuardianConsentVersion.SUPERSEDED);
            previous.setSupersededByVersionId(version.getId());
            previous.setSupersededAt(now);
            versionRepository.save(previous);
            logConsent(consent, previous.getId(), previous.getVersionNo(), "SUPERSEDE",
                    GuardianConsent.VALID, GuardianConsentVersion.SUPERSEDED, "授权系统", "SYSTEM",
                    "v" + version.getVersionNo() + " 确认生效，v" + previous.getVersionNo()
                            + " 转已被取代：旧版本仍可查询但不得继续放行");
            // 接管后基线漂移：钉住旧版本的在途交接立即冻结未开始动作
            freezeVisits(previous, "授权 v" + previous.getVersionNo() + " 已被 v"
                    + version.getVersionNo() + " 取代，未开始的课程 / 箭道 / 器材动作按新基线冻结", now);
        }
        return detail(id);
    }

    // ---------------- 撤回待确认：PENDING_CONFIRM → DRAFT（保留版本，改内容走新版本） ----------------

    @Transactional
    public GuardianDtos.ConsentView recall(Long id, GuardianDtos.OperatorReq req) {
        GuardianConsent consent = lockConsent(id);
        GuardianConsentVersion version = currentVersion(consent);
        String operator = requireName(req.operator(), "前台");
        if (!GuardianConsentVersion.PENDING_CONFIRM.equals(version.getStatus())) {
            throw new BizException("只有待监护人确认的版本才能撤回，v" + version.getVersionNo() + " 当前为「"
                    + RangeDict.consentVersionStatusName(version.getStatus()) + "」");
        }
        LocalDateTime now = LocalDateTime.now();
        version.setStatus(GuardianConsentVersion.DRAFT);
        versionRepository.save(version);
        consent.setStatus(GuardianConsent.DRAFT);
        consent.setUpdatedAt(now);
        consentRepository.save(consent);
        logConsent(consent, version.getId(), version.getVersionNo(), "RECALL",
                GuardianConsent.PENDING_CONFIRM, GuardianConsent.DRAFT, operator, "FRONT",
                "撤回 v" + version.getVersionNo() + " 的确认请求（范围需调整时请发布新版本）"
                        + reasonSuffix(req.reason()));
        return detail(id);
    }

    // ---------------- 发布新版本草稿（范围 / 日期 / 接管人变化的唯一入口） ----------------

    @Transactional
    public GuardianDtos.ConsentView newVersion(Long id, GuardianDtos.NewVersionReq req) {
        GuardianConsent consent = lockConsent(id);
        GuardianConsentVersion current = currentVersion(consent);
        String operator = requireName(req.operator(), "前台");
        GuardianDtos.VersionContent content = validateContent(req.version());

        // 草稿 / 待监护人确认的版本必须先处理（确认 / 撤回）；已拒绝版本关闭，可直接在其上发新版。
        if (GuardianConsentVersion.DRAFT.equals(current.getStatus())
                || GuardianConsentVersion.PENDING_CONFIRM.equals(current.getStatus())) {
            throw new BizException("v" + current.getVersionNo() + " 仍为「"
                    + RangeDict.consentVersionStatusName(current.getStatus())
                    + "」，请先完成或撤回该版本，再发布新版本");
        }
        // 撤销链发新版本：链重新进入草稿流程（旧撤销事实保留）
        int nextNo = (int) versionRepository.countByConsentId(id) + 1;
        LocalDateTime now = LocalDateTime.now();
        GuardianConsentVersion version = writeVersion(consent, nextNo, content, operator, now);

        // 旧 VALID 版本在新版本草稿建立的同一事务内立即转 SUPERSEDED：
        // 范围 / 日期 / 接管人一旦申报变更，旧版本即不得继续放行（与版本化规则「发新版即停旧版」一致）。
        // 钉住旧版本的在途交接同时冻结未开始动作；新版本确认前不存在放行窗口。
        GuardianConsentVersion superseded = null;
        String chainBefore = consent.getStatus();
        if (GuardianConsentVersion.VALID.equals(current.getStatus())) {
            current.setStatus(GuardianConsentVersion.SUPERSEDED);
            current.setSupersededByVersionId(version.getId());
            current.setSupersededAt(now);
            versionRepository.save(current);
            superseded = current;
        }
        consent.setStatus(GuardianConsent.DRAFT);
        consent.setCurrentVersionId(version.getId());
        consent.setUpdatedAt(now);
        consentRepository.save(consent);
        logConsent(consent, version.getId(), nextNo, "NEW_VERSION",
                chainBefore, GuardianConsent.DRAFT, operator, "FRONT",
                "基于 v" + current.getVersionNo() + " 发布 v" + nextNo + " 草稿，变更内容："
                        + diffSummary(current, content)
                        + (superseded == null ? ""
                                : "；v" + current.getVersionNo() + " 立即转已被取代，新版本确认前不得继续放行"));
        if (superseded != null) {
            logConsent(consent, superseded.getId(), superseded.getVersionNo(), "SUPERSEDE",
                    GuardianConsent.VALID, GuardianConsentVersion.SUPERSEDED, "授权系统", "SYSTEM",
                    "监护人申报范围变更，v" + superseded.getVersionNo()
                            + " 随新版本草稿发布转已被取代，等待监护人确认 v" + nextNo);
            freezeVisits(superseded, "授权基线发生变化：v" + superseded.getVersionNo()
                    + " 已被申报变更的 v" + nextNo + " 草稿取代，未开始的课程 / 箭道 / 器材动作立即冻结，"
                    + "须待 v" + nextNo + " 监护人确认并由工作人员重新核对", now);
        }
        return detail(id);
    }

    // ---------------- 暂停 / 恢复（值班经理） ----------------

    @Transactional
    public GuardianDtos.ConsentView suspend(Long id, GuardianDtos.OperatorReq req) {
        GuardianConsent consent = lockConsent(id);
        GuardianConsentVersion version = currentVersion(consent);
        String operator = requireName(req.operator(), "值班经理");
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            throw new BizException("暂停授权必须填写原因");
        }
        if (!GuardianConsent.VALID.equals(consent.getStatus())
                || !GuardianConsentVersion.VALID.equals(version.getStatus())) {
            throw new BizException("只有有效授权可以暂停，当前为「"
                    + RangeDict.consentStatusName(consent.getStatus()) + "」");
        }
        LocalDateTime now = LocalDateTime.now();
        consent.setStatus(GuardianConsent.SUSPENDED);
        consent.setUpdatedAt(now);
        consentRepository.save(consent);
        version.setStatus(GuardianConsentVersion.SUSPENDED);
        version.setSuspendedBy(operator);
        version.setSuspendedAt(now);
        version.setSuspendReason(reason);
        versionRepository.save(version);
        logConsent(consent, version.getId(), version.getVersionNo(), "SUSPEND",
                GuardianConsent.VALID, GuardianConsent.SUSPENDED, operator, "MANAGER",
                "暂停 v" + version.getVersionNo() + "：" + reason);
        freezeVisits(version, "授权 v" + version.getVersionNo() + " 已被值班经理暂停（"
                + reason + "），未开始动作立即冻结", now);
        return detail(id);
    }

    @Transactional
    public GuardianDtos.ConsentView resume(Long id, GuardianDtos.OperatorReq req) {
        GuardianConsent consent = lockConsent(id);
        GuardianConsentVersion version = currentVersion(consent);
        String operator = requireName(req.operator(), "值班经理");
        if (!GuardianConsent.SUSPENDED.equals(consent.getStatus())
                || !GuardianConsentVersion.SUSPENDED.equals(version.getStatus())) {
            throw new BizException("只有暂停状态的授权可以恢复，当前为「"
                    + RangeDict.consentStatusName(consent.getStatus()) + "」");
        }
        LocalDate today = LocalDate.now();
        if (version.getEffectiveUntil().isBefore(today)) {
            throw new BizException("v" + version.getVersionNo() + " 终止日 " + version.getEffectiveUntil()
                    + " 已过，恢复无意义；请发布新版本并重新确认");
        }
        LocalDateTime now = LocalDateTime.now();
        consent.setStatus(GuardianConsent.VALID);
        consent.setUpdatedAt(now);
        consentRepository.save(consent);
        version.setStatus(GuardianConsentVersion.VALID);
        versionRepository.save(version);
        logConsent(consent, version.getId(), version.getVersionNo(), "RESUME",
                GuardianConsent.SUSPENDED, GuardianConsent.VALID, operator, "MANAGER",
                "恢复 v" + version.getVersionNo() + " 有效；在途交接仍须工作人员按基线重新核对后才能继续放行"
                        + reasonSuffix(req.reason()));
        // 注意：恢复不自动解冻在途交接——基线曾经变化，必须由工作人员显式重新核对（见 visit recheck）。
        return detail(id);
    }

    // ---------------- 撤销（值班经理，终态） ----------------

    @Transactional
    public GuardianDtos.ConsentView revoke(Long id, GuardianDtos.OperatorReq req) {
        GuardianConsent consent = lockConsent(id);
        GuardianConsentVersion version = currentVersion(consent);
        String operator = requireName(req.operator(), "值班经理");
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            throw new BizException("撤销授权必须填写原因");
        }
        if (GuardianConsent.REVOKED.equals(consent.getStatus())
                || GuardianConsentVersion.REVOKED.equals(version.getStatus())) {
            throw new BizException("授权已撤销，请勿重复操作");
        }
        if (GuardianConsent.DRAFT.equals(consent.getStatus())
                || GuardianConsent.PENDING_CONFIRM.equals(consent.getStatus())) {
            throw new BizException("草稿 / 待确认状态不能撤销，请直接撤回或拒绝");
        }
        LocalDateTime now = LocalDateTime.now();
        String before = consent.getStatus();
        consent.setStatus(GuardianConsent.REVOKED);
        consent.setUpdatedAt(now);
        consentRepository.save(consent);
        version.setStatus(GuardianConsentVersion.REVOKED);
        version.setRevokedBy(operator);
        version.setRevokedAt(now);
        version.setRevokeReason(reason);
        versionRepository.save(version);
        logConsent(consent, version.getId(), version.getVersionNo(), "REVOKE",
                before, GuardianConsent.REVOKED, operator, "MANAGER",
                "撤销 v" + version.getVersionNo() + "：" + reason + "；撤销后旧版本可查但立即停止一切放行");
        freezeVisits(version, "授权 v" + version.getVersionNo() + " 已被撤销（"
                + reason + "），未开始动作立即冻结，已开始训练须安全交还", now);
        return detail(id);
    }

    // ---------------- 供交接侧使用的只读判断 ----------------

    /** 当前可用于接管 / 报名门禁的有效版本；不存在 / 状态不对 / 不在日期区间 → BizException（带明确原因） */
    @Transactional(readOnly = true)
    public GuardianConsentVersion requireEffectiveVersion(Member member) {
        List<GuardianConsent> chains = consentRepository.findByMemberIdOrderByIdDesc(member.getId());
        if (chains.isEmpty()) {
            throw new BizException("未成年会员 [" + member.getName() + "] 尚未建立监护授权，"
                    + "请先到监护授权台建档并由监护人确认");
        }
        GuardianConsent chain = chains.get(0);
        GuardianConsentVersion version = chain.getCurrentVersionId() == null ? null
                : versionRepository.findById(chain.getCurrentVersionId()).orElse(null);
        if (version == null) {
            throw new BizException("会员 [" + member.getName() + "] 的监护授权缺少有效版本，请联系前台");
        }
        assertVersionEffective(chain, version);
        return version;
    }

    /** 只读判断（不抛异常的版本，供门禁组合提示） */
    @Transactional(readOnly = true)
    public GuardianConsentVersion findEffectiveVersion(Long memberId) {
        List<GuardianConsent> chains = consentRepository.findByMemberIdOrderByIdDesc(memberId);
        if (chains.isEmpty()) {
            return null;
        }
        GuardianConsent chain = chains.get(0);
        if (chain.getCurrentVersionId() == null) {
            return null;
        }
        GuardianConsentVersion version = versionRepository.findById(chain.getCurrentVersionId()).orElse(null);
        if (version == null || !GuardianConsentVersion.VALID.equals(version.getStatus())) {
            return null;
        }
        LocalDate today = LocalDate.now();
        if (today.isBefore(version.getEffectiveFrom()) || today.isAfter(version.getEffectiveUntil())) {
            return null;
        }
        return version;
    }

    /** 课程是否被某版本范围覆盖 */
    @Transactional(readOnly = true)
    public boolean coversCourse(GuardianConsentVersion version, Long courseId) {
        return courseScopeRepository.findByVersionId(version.getId()).stream()
                .anyMatch(item -> item.getCourseId().equals(courseId));
    }

    // ---------------- 内部：版本与范围整体写入 ----------------

    /** 校验一组授权内容：去重、非空、资源全部存在；任一失败整体拒绝（调用方事务回滚，不留半成品） */
    private GuardianDtos.VersionContent validateContent(GuardianDtos.VersionContent raw) {
        if (raw == null) {
            throw new BizException("请填写授权版本内容");
        }
        if (raw.effectiveFrom() == null || raw.effectiveUntil() == null) {
            throw new BizException("请选择授权生效日与终止日");
        }
        if (raw.effectiveUntil().isBefore(raw.effectiveFrom())) {
            throw new BizException("终止日不能早于生效日");
        }
        if (raw.effectiveUntil().isBefore(LocalDate.now())) {
            throw new BizException("终止日不能早于今天，历史授权不能补登记");
        }
        List<Long> courseIds = distinct(raw.courseIds(), "课程");
        List<Long> laneIds = distinct(raw.laneIds(), "箭道");
        List<Long> equipIds = distinct(raw.equipmentIds(), "器材");
        if (courseIds.isEmpty()) {
            throw new BizException("请至少选择一门允许参与的课程（范围作为整体，不能留空）");
        }
        if (laneIds.isEmpty()) {
            throw new BizException("请至少选择一条可使用的箭道");
        }
        if (equipIds.isEmpty()) {
            throw new BizException("请至少选择一件可使用的器材");
        }
        List<Course> courses = courseRepository.findAllById(courseIds);
        if (courses.size() != courseIds.size()) {
            throw new BizException("所选课程中有不存在的项目，请刷新后重试");
        }
        List<Lane> lanes = laneRepository.findAllById(laneIds);
        if (lanes.size() != laneIds.size()) {
            throw new BizException("所选箭道中有不存在的道号，请刷新后重试");
        }
        List<Equipment> equips = equipmentRepository.findAllById(equipIds);
        if (equips.size() != equipIds.size()) {
            throw new BizException("所选器材中有不存在的编号，请刷新后重试");
        }
        List<GuardianDtos.PickupPersonReq> pickups = raw.pickups() == null ? List.of() : raw.pickups();
        if (pickups.isEmpty()) {
            throw new BizException("请至少登记一名允许承担现场接管责任的人员");
        }
        LinkedHashSet<String> pickupKeys = new LinkedHashSet<>();
        for (GuardianDtos.PickupPersonReq p : pickups) {
            String name = requireName(p.name(), "接管人");
            String idNo = requireName(p.idNo(), "接管人证件标识");
            requirePhone(p.phone(), "接管人手机号");
            if (!RangeDict.isValidGuardianRelation(p.relation())) {
                throw new BizException("接管人 [" + name + "] 的关系只能是：父亲 / 母亲 / 祖父母 / 其他法定监护人");
            }
            if (!pickupKeys.add(name + "#" + idNo)) {
                throw new BizException("接管人 [" + name + "] 证件标识重复，同一人只能登记一次");
            }
        }
        return new GuardianDtos.VersionContent(raw.effectiveFrom(), raw.effectiveUntil(),
                courseIds, laneIds, equipIds, new ArrayList<>(pickups), trimToNull(raw.remark()));
    }

    private GuardianConsentVersion writeVersion(GuardianConsent consent, int versionNo,
            GuardianDtos.VersionContent content, String operator, LocalDateTime now) {
        GuardianConsentVersion version = new GuardianConsentVersion();
        version.setConsent(consent);
        version.setVersionNo(versionNo);
        version.setStatus(GuardianConsentVersion.DRAFT);
        version.setEffectiveFrom(content.effectiveFrom());
        version.setEffectiveUntil(content.effectiveUntil());
        version.setVersionRemark(trimToNull(content.remark()));
        version.setContentFingerprint(fingerprint(versionNo, content));
        version.setCreatedBy(operator);
        version.setCreatedAt(now);
        versionRepository.save(version);
        fillScopeRows(version, content);
        return version;
    }

    /** 范围四表随版本一次性整体写入（调用前已完成资源存在性校验） */
    private void fillScopeRows(GuardianConsentVersion version, GuardianDtos.VersionContent content) {
        for (Long courseId : content.courseIds()) {
            GuardianConsentCourse row = new GuardianConsentCourse();
            row.setVersion(version);
            row.setCourseId(courseId);
            courseScopeRepository.save(row);
        }
        for (Long laneId : content.laneIds()) {
            GuardianConsentLane row = new GuardianConsentLane();
            row.setVersion(version);
            row.setLaneId(laneId);
            laneScopeRepository.save(row);
        }
        for (Long equipId : content.equipmentIds()) {
            GuardianConsentEquipment row = new GuardianConsentEquipment();
            row.setVersion(version);
            row.setEquipmentId(equipId);
            equipScopeRepository.save(row);
        }
        for (GuardianDtos.PickupPersonReq p : content.pickups()) {
            GuardianConsentPickup row = new GuardianConsentPickup();
            row.setVersion(version);
            row.setPersonName(p.name().trim());
            row.setIdNo(p.idNo().trim());
            row.setPhone(p.phone().trim());
            row.setRelation(p.relation());
            pickupRepository.save(row);
        }
    }

    /** 草稿重写：整组清除旧范围（仅对从未确认的 DRAFT 版本调用）；先 flush 删除再插新行，避免唯一键冲突 */
    private void clearScopeRows(Long versionId) {
        courseScopeRepository.deleteAll(courseScopeRepository.findByVersionId(versionId));
        laneScopeRepository.deleteAll(laneScopeRepository.findByVersionId(versionId));
        equipScopeRepository.deleteAll(equipScopeRepository.findByVersionId(versionId));
        pickupRepository.deleteAll(pickupRepository.findByVersionId(versionId));
        courseScopeRepository.flush();
    }

    // ---------------- 内部：基线漂移联动冻结 ----------------

    /**
     * 授权基线变化后冻结在途交接：已发生的接管 / 已放行资源事实不改写，
     * 仅把交接单 baselineFrozen 置 1，并把尚未放行（PENDING）的资源台账项置 FROZEN。
     */
    private void freezeVisits(GuardianConsentVersion changedFromVersion, String reason, LocalDateTime now) {
        List<MinorVisit> openVisits = visitRepository.findOpenByBaselineVersionForUpdate(changedFromVersion.getId());
        for (MinorVisit visit : openVisits) {
            if (visit.isBaselineFrozen()) {
                continue; // 已冻结不重复制造轨迹
            }
            visit.setBaselineFrozen(true);
            visit.setFreezeReason(reason);
            visit.setFrozenAt(now);
            visitRepository.save(visit);
            List<MinorVisitResource> resources = visitResourceRepository.findByVisitIdOrderByIdAsc(visit.getId());
            for (MinorVisitResource resource : resources) {
                if (MinorVisitResource.PENDING.equals(resource.getStatus())) {
                    resource.setStatus(MinorVisitResource.FROZEN);
                    visitResourceRepository.save(resource);
                }
            }
            writeVisitLog(visit, "FREEZE", visit.getStatus(), "授权系统", "SYSTEM", reason);
        }
    }

    private void writeVisitLog(MinorVisit visit, String action, String stage,
            String operator, String role, String detail) {
        var log = new com.archery.range.domain.MinorVisitLog();
        log.setVisit(visit);
        log.setAction(action);
        log.setStage(stage);
        log.setOperator(operator);
        log.setRole(role);
        log.setDetail(detail);
        log.setCreatedAt(LocalDateTime.now());
        visitLogRepository.save(log);
    }

    // ---------------- 内部：校验 / 指纹 / 编号 / 转换 ----------------

    private void assertVersionEffective(GuardianConsent chain, GuardianConsentVersion version) {
        if (GuardianConsent.REVOKED.equals(chain.getStatus())
                || GuardianConsentVersion.REVOKED.equals(version.getStatus())) {
            throw new BizException("监护授权 v" + version.getVersionNo() + " 已撤销，不能放行任何课程 / 箭道 / 器材");
        }
        if (GuardianConsent.SUSPENDED.equals(chain.getStatus())
                || GuardianConsentVersion.SUSPENDED.equals(version.getStatus())) {
            throw new BizException("监护授权 v" + version.getVersionNo() + " 处于暂停状态，待恢复并重新核对后才能放行");
        }
        if (GuardianConsentVersion.SUPERSEDED.equals(version.getStatus())) {
            throw new BizException("监护授权 v" + version.getVersionNo()
                    + " 已被新版本取代，旧版本不得继续放行，请按当前版本重新核对");
        }
        if (!GuardianConsentVersion.VALID.equals(version.getStatus())
                || !GuardianConsent.VALID.equals(chain.getStatus())) {
            throw new BizException("监护授权 v" + version.getVersionNo() + " 当前为「"
                    + RangeDict.consentVersionStatusName(version.getStatus()) + "」，尚未完成监护人确认，不能放行");
        }
        LocalDate today = LocalDate.now();
        if (today.isBefore(version.getEffectiveFrom())) {
            throw new BizException("监护授权 v" + version.getVersionNo() + " 自 "
                    + version.getEffectiveFrom() + " 起生效，当前尚未生效");
        }
        if (today.isAfter(version.getEffectiveUntil())) {
            throw new BizException("监护授权 v" + version.getVersionNo() + " 已于 "
                    + version.getEffectiveUntil() + " 到期，请发布新版本并重新确认");
        }
    }

    /** 版本内容指纹：版本号 + 日期 + 排序后的范围 + 接管人名单，任何内容变化指纹必变 */
    private String fingerprint(int versionNo, GuardianDtos.VersionContent content) {
        List<String> pickupParts = content.pickups().stream()
                .map(p -> String.join("|", p.name().trim(), p.idNo().trim(), p.phone().trim(), p.relation()))
                .sorted()
                .toList();
        String raw = "v=" + versionNo
                + "|from=" + content.effectiveFrom()
                + "|until=" + content.effectiveUntil()
                + "|courses=" + content.courseIds().stream().sorted().toList()
                + "|lanes=" + content.laneIds().stream().sorted().toList()
                + "|equips=" + content.equipmentIds().stream().sorted().toList()
                + "|pickups=" + pickupParts
                + "|remark=" + (content.remark() == null ? "" : content.remark().trim());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String diffSummary(GuardianConsentVersion oldVersion, GuardianDtos.VersionContent newContent) {
        List<Long> oldCourses = courseScopeRepository.findByVersionId(oldVersion.getId()).stream()
                .map(GuardianConsentCourse::getCourseId).sorted().toList();
        List<Long> oldLanes = laneScopeRepository.findByVersionId(oldVersion.getId()).stream()
                .map(GuardianConsentLane::getLaneId).sorted().toList();
        List<Long> oldEquips = equipScopeRepository.findByVersionId(oldVersion.getId()).stream()
                .map(GuardianConsentEquipment::getEquipmentId).sorted().toList();
        List<String> oldPickups = pickupRepository.findByVersionId(oldVersion.getId()).stream()
                .map(GuardianConsentPickup::getPersonName).sorted().toList();
        List<String> changes = new ArrayList<>();
        if (!oldVersion.getEffectiveFrom().equals(newContent.effectiveFrom())
                || !oldVersion.getEffectiveUntil().equals(newContent.effectiveUntil())) {
            changes.add("有效期 " + oldVersion.getEffectiveFrom() + "~" + oldVersion.getEffectiveUntil()
                    + " → " + newContent.effectiveFrom() + "~" + newContent.effectiveUntil());
        }
        if (!oldCourses.equals(newContent.courseIds().stream().sorted().toList())) {
            changes.add("课程范围调整");
        }
        if (!oldLanes.equals(newContent.laneIds().stream().sorted().toList())) {
            changes.add("箭道范围调整");
        }
        if (!oldEquips.equals(newContent.equipmentIds().stream().sorted().toList())) {
            changes.add("器材范围调整");
        }
        if (!oldPickups.equals(newContent.pickups().stream().map(p -> p.name().trim()).sorted().toList())) {
            changes.add("接管人名单调整");
        }
        return changes.isEmpty() ? "内容无变化（仍按新版本重新确认）" : String.join("；", changes);
    }

    private GuardianConsent lockConsent(Long id) {
        return consentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("监护授权链不存在：id=" + id));
    }

    private GuardianConsent requireConsent(Long id) {
        return consentRepository.findById(id)
                .orElseThrow(() -> new BizException("监护授权链不存在：id=" + id));
    }

    private GuardianConsentVersion currentVersion(GuardianConsent consent) {
        if (consent.getCurrentVersionId() == null) {
            throw new BizException("授权链缺少当前版本，请刷新后重试");
        }
        return versionRepository.findById(consent.getCurrentVersionId())
                .orElseThrow(() -> new BizException("当前版本不存在，请刷新后重试"));
    }

    private GuardianConsentVersion currentDraftVersion(GuardianConsent consent) {
        GuardianConsentVersion version = currentVersion(consent);
        if (!GuardianConsentVersion.DRAFT.equals(version.getStatus())) {
            throw new BizException("v" + version.getVersionNo() + " 已提交确认，范围 / 日期 / 接管人变化必须发布新版本，不能就地改写");
        }
        return version;
    }

    private void logConsent(GuardianConsent consent, Long versionId, Integer versionNo, String action,
            String before, String after, String operator, String role, String detail) {
        GuardianConsentLog log = new GuardianConsentLog();
        log.setConsent(consent);
        log.setVersionId(versionId);
        log.setVersionNo(versionNo);
        log.setAction(action);
        log.setBeforeStatus(before);
        log.setAfterStatus(after);
        log.setOperator(operator);
        log.setRole(role);
        log.setDetail(detail);
        log.setCreatedAt(LocalDateTime.now());
        logRepository.save(log);
    }

    private String nextConsentNo() {
        String no = "G" + LocalDateTime.now().format(NO_STAMP);
        int seq = 1;
        while (consentRepository.existsByConsentNo(no)) {
            no = "G" + LocalDateTime.now().format(NO_STAMP) + seq;
            seq += 1;
        }
        return no;
    }

    private List<Long> distinct(List<Long> ids, String label) {
        List<Long> source = ids == null ? List.of() : ids;
        LinkedHashSet<Long> set = new LinkedHashSet<>();
        for (Long id : source) {
            if (id == null) {
                throw new BizException(label + "选择包含空值，请刷新后重试");
            }
            set.add(id);
        }
        return new ArrayList<>(set);
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

    private String reasonSuffix(String reason) {
        String r = reason == null ? "" : reason.trim();
        return r.isEmpty() ? "" : "；说明：" + r;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    // ---------------- 内部：出参转换（供本服务与交接服务共用） ----------------

    public GuardianDtos.VersionView toVersionView(GuardianConsentVersion version) {
        Long vid = version.getId();
        List<GuardianConsentCourse> courseRows = courseScopeRepository.findByVersionId(vid);
        Map<Long, Course> courseMap = courseRepository.findAllById(
                courseRows.stream().map(GuardianConsentCourse::getCourseId).toList()).stream()
                .collect(Collectors.toMap(Course::getId, item -> item));
        List<GuardianDtos.ScopeCourseView> courses = courseRows.stream().map(row -> {
            Course course = courseMap.get(row.getCourseId());
            if (course == null) {
                return new GuardianDtos.ScopeCourseView(row.getCourseId(), "(课程已删除)", "", "", null, "");
            }
            return new GuardianDtos.ScopeCourseView(course.getId(), course.getCourseName(), course.getCoach(),
                    RangeDict.courseLevelName(course.getLevel()), course.getClassTime(), course.getVenue());
        }).toList();

        List<GuardianConsentLane> laneRows = laneScopeRepository.findByVersionId(vid);
        Map<Long, Lane> laneMap = laneRepository.findAllById(
                laneRows.stream().map(GuardianConsentLane::getLaneId).toList()).stream()
                .collect(Collectors.toMap(Lane::getId, item -> item));
        List<GuardianDtos.ScopeLaneView> lanes = laneRows.stream().map(row -> {
            Lane lane = laneMap.get(row.getLaneId());
            if (lane == null) {
                return new GuardianDtos.ScopeLaneView(row.getLaneId(), "(箭道已删除)", null, "");
            }
            return new GuardianDtos.ScopeLaneView(lane.getId(), lane.getLaneNo(), lane.getDistance(),
                    lane.getTargetType());
        }).toList();

        List<GuardianConsentEquipment> equipRows = equipScopeRepository.findByVersionId(vid);
        Map<Long, Equipment> equipMap = equipmentRepository.findAllById(
                equipRows.stream().map(GuardianConsentEquipment::getEquipmentId).toList()).stream()
                .collect(Collectors.toMap(Equipment::getId, item -> item));
        List<GuardianDtos.ScopeEquipmentView> equips = equipRows.stream().map(row -> {
            Equipment equip = equipMap.get(row.getEquipmentId());
            if (equip == null) {
                return new GuardianDtos.ScopeEquipmentView(row.getEquipmentId(), "(器材已删除)", "", "");
            }
            return new GuardianDtos.ScopeEquipmentView(equip.getId(), equip.getEquipCode(),
                    RangeDict.equipTypeName(equip.getType()), equip.getBrand());
        }).toList();

        List<GuardianDtos.PickupPersonView> pickups = pickupRepository.findByVersionId(vid).stream()
                .map(p -> new GuardianDtos.PickupPersonView(p.getPersonName(), p.getIdNo(), p.getPhone(),
                        p.getRelation(), RangeDict.guardianRelationName(p.getRelation())))
                .toList();

        return new GuardianDtos.VersionView(
                version.getId(),
                version.getVersionNo(),
                version.getStatus(),
                RangeDict.consentVersionStatusName(version.getStatus()),
                version.getEffectiveFrom(),
                version.getEffectiveUntil(),
                version.getVersionRemark(),
                version.getConfirmedBy(),
                version.getConfirmedAt(),
                version.getConfirmRelation(),
                version.getConfirmNote(),
                version.getRejectedBy(),
                version.getRejectedAt(),
                version.getRejectReason(),
                version.getSupersededByVersionId(),
                version.getSupersededAt(),
                version.getSuspendedBy(),
                version.getSuspendedAt(),
                version.getSuspendReason(),
                version.getRevokedBy(),
                version.getRevokedAt(),
                version.getRevokeReason(),
                version.getContentFingerprint(),
                version.getCreatedBy(),
                version.getCreatedAt(),
                courses,
                lanes,
                equips,
                pickups);
    }

    private GuardianDtos.ConsentLogView toLogView(GuardianConsentLog log) {
        return new GuardianDtos.ConsentLogView(
                log.getId(),
                log.getVersionId(),
                log.getVersionNo(),
                log.getAction(),
                RangeDict.guardianActionName(log.getAction()),
                log.getBeforeStatus(),
                log.getBeforeStatus() == null ? ""
                        : (log.getVersionId() == null
                                ? RangeDict.consentStatusName(log.getBeforeStatus())
                                : RangeDict.consentVersionStatusName(log.getBeforeStatus())),
                log.getAfterStatus(),
                log.getAfterStatus() == null ? ""
                        : (log.getVersionId() == null
                                ? RangeDict.consentStatusName(log.getAfterStatus())
                                : RangeDict.consentVersionStatusName(log.getAfterStatus())),
                log.getOperator(),
                log.getRole(),
                RangeDict.guardianRoleName(log.getRole()),
                log.getDetail(),
                log.getCreatedAt());
    }

    private GuardianDtos.ConsentSummary toSummary(GuardianConsent consent) {
        GuardianConsentVersion current = consent.getCurrentVersionId() == null ? null
                : versionRepository.findById(consent.getCurrentVersionId()).orElse(null);
        long versionCount = versionRepository.countByConsentId(consent.getId());
        List<MinorVisit> openVisits = visitRepository.findByMemberIdAndStatusInOrderByIdDesc(
                consent.getMember().getId(),
                List.of(MinorVisit.PENDING_PICKUP, MinorVisit.TRAINING, MinorVisit.PENDING_RETURN));
        return new GuardianDtos.ConsentSummary(
                consent.getId(),
                consent.getConsentNo(),
                consent.getMember().getId(),
                consent.getMember().getName(),
                consent.getMember().getCardNo(),
                consent.getGuardianName(),
                consent.getGuardianPhone(),
                consent.getGuardianRelation(),
                RangeDict.guardianRelationName(consent.getGuardianRelation()),
                consent.getStatus(),
                RangeDict.consentStatusName(consent.getStatus()),
                consent.getCurrentVersionId(),
                current == null ? null : current.getVersionNo(),
                current == null ? null : current.getEffectiveFrom(),
                current == null ? null : current.getEffectiveUntil(),
                consent.getEmergencyContactName(),
                consent.getEmergencyContactPhone(),
                consent.getRemark(),
                consent.getCreatedBy(),
                consent.getCreatedAt(),
                consent.getUpdatedAt(),
                (int) versionCount,
                !openVisits.isEmpty());
    }

    /** 选项词典（建链 / 发版表单用） */
    @Transactional(readOnly = true)
    public Map<String, Object> options() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("relations", List.of(
                Map.of("code", "FATHER", "name", RangeDict.guardianRelationName("FATHER")),
                Map.of("code", "MOTHER", "name", RangeDict.guardianRelationName("MOTHER")),
                Map.of("code", "GRANDPARENT", "name", RangeDict.guardianRelationName("GRANDPARENT")),
                Map.of("code", "OTHER", "name", RangeDict.guardianRelationName("OTHER"))));
        options.put("courses", courseRepository.findAllByOrderByClassTimeAsc().stream()
                .map(c -> Map.<String, Object>of("id", c.getId(), "name", c.getCourseName(),
                        "coach", c.getCoach(), "level", RangeDict.courseLevelName(c.getLevel())))
                .toList());
        options.put("lanes", laneRepository.findAllByOrderByLaneNoAsc().stream()
                .map(l -> Map.<String, Object>of("id", l.getId(), "laneNo", l.getLaneNo(),
                        "distance", l.getDistance(), "target", l.getTargetType(),
                        "status", l.getStatus()))
                .toList());
        options.put("equipment", equipmentRepository.findAllByOrderByEquipCodeAsc().stream()
                .map(e -> Map.<String, Object>of("id", e.getId(), "code", e.getEquipCode(),
                        "type", RangeDict.equipTypeName(e.getType()), "brand", e.getBrand(),
                        "status", e.getStatus()))
                .toList());
        options.put("minors", memberRepository
                .findByMinorFlagTrueAndStatusOrderByIdAsc(Member.STATUS_ACTIVE).stream()
                .map(m -> Map.<String, Object>of("id", m.getId(), "name", m.getName(),
                        "cardNo", m.getCardNo(), "birthDate", String.valueOf(m.getBirthDate())))
                .toList());
        return options;
    }
}
