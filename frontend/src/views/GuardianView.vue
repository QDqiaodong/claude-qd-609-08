<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">
        未成年会员监护授权台
        <em>同一版本授权 · 接管核验 · 整组放行 · 安全交还 · 全程可追溯</em>
      </h2>
      <div class="page-tools">
        <el-radio-group v-model="operatorRole" size="small">
          <el-radio-button v-for="r in roles" :key="r.code" :value="r.code">{{ r.name }}</el-radio-button>
        </el-radio-group>
        <el-input v-model="operator" placeholder="当前操作人姓名" style="width: 150px" />
      </div>
    </div>

    <div class="rule-tip">
      每份监护授权记录未成年会员、监护人、授权版本、生效/终止日、课程 / 箭道 / 器材范围、接管人名单、紧急联系人与备注；
      监护人确认后才生效，范围或接管人变化必须发新版本重新确认，旧版本可查但不再放行。
      每次到馆独立交接：<b>待接管 → 训练中 → 待交还 → 已交还</b>，接管后授权缩小 / 暂停 / 撤销会冻结未开始动作，
      未完成交还不能关闭、也不能开启下一次到馆。当前角色：<b>{{ roleName }}</b>。
    </div>

    <el-tabs v-model="tab" class="g-tabs">
      <!-- ================= 授权台账 ================= -->
      <el-tab-pane name="consents">
        <div class="board">
          <div class="panel list-panel">
            <div class="panel-title">
              监护授权链
              <small>待确认 <b class="c-warn">{{ pendingCount }}</b> 份 · 有效 {{ validCount }} 份</small>
              <el-button size="small" type="primary" plain style="margin-left: auto" @click="openCreate">
                建立授权链
              </el-button>
            </div>
            <el-table :data="consents" height="560" highlight-current-row @current-change="onPickConsent">
              <el-table-column prop="consentNo" label="授权号" width="140">
                <template #default="{ row }"><span class="mono">{{ row.consentNo }}</span></template>
              </el-table-column>
              <el-table-column label="未成年会员" width="150">
                <template #default="{ row }">{{ row.memberName }}<div class="muted">{{ row.cardNo }}</div></template>
              </el-table-column>
              <el-table-column label="监护人" min-width="120">
                <template #default="{ row }">
                  {{ row.guardianName }}（{{ row.guardianRelationName }}）
                  <div class="muted">{{ row.guardianPhone }}</div>
                </template>
              </el-table-column>
              <el-table-column label="当前版本" width="90">
                <template #default="{ row }">
                  <span v-if="row.currentVersionNo">v{{ row.currentVersionNo }}</span>
                  <span v-else class="muted">-</span>
                </template>
              </el-table-column>
              <el-table-column label="有效期" width="200">
                <template #default="{ row }">
                  <span v-if="row.currentEffectiveFrom">{{ shortDate(row.currentEffectiveFrom) }} ~ {{ shortDate(row.currentEffectiveUntil) }}</span>
                  <span v-else class="muted">-</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <el-tag size="small" :type="consentStatusType(row.status)" effect="dark">{{ row.statusName }}</el-tag>
                  <el-tag v-if="row.openVisit" size="small" type="danger" effect="plain" style="margin-left: 4px">在馆</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="panel detail-panel">
            <template v-if="consent">
              <div class="detail-head">
                <div>
                  <div class="detail-title">
                    {{ consent.summary.memberName }} 的监护授权
                    <el-tag size="small" :type="consentStatusType(consent.summary.status)" effect="dark">
                      {{ consent.summary.statusName }}
                    </el-tag>
                    <span class="mono muted">{{ consent.summary.consentNo }}</span>
                  </div>
                  <div class="muted">
                    紧急联系人：{{ consent.summary.emergencyContactName }} {{ consent.summary.emergencyContactPhone }}
                    <span v-if="consent.summary.remark"> · 备注：{{ consent.summary.remark }}</span>
                  </div>
                </div>
              </div>

              <!-- 当前版本动作条 -->
              <div class="action-bar">
                <template v-for="v in [currentVersion]">
                  <template v-if="v">
                    <el-button v-if="v.status === 'DRAFT'" size="small" type="primary" @click="openEditDraft(v)">
                      修改草稿
                    </el-button>
                    <el-button v-if="v.status === 'DRAFT'" size="small" type="success"
                      @click="act('submit', { operator })">提交监护人确认</el-button>
                    <template v-if="v.status === 'PENDING_CONFIRM'">
                      <el-button size="small" type="success" @click="openConfirm(false)">监护人确认</el-button>
                      <el-button size="small" type="danger" plain @click="openConfirm(true)">监护人拒绝</el-button>
                      <el-button size="small" @click="withReason('recall', '撤回到草稿')">撤回到草稿</el-button>
                    </template>
                    <el-button v-if="['VALID','SUSPENDED','REVOKED','SUPERSEDED'].includes(v.status)"
                      size="small" type="warning" plain @click="openNewVersion(v)">发布新版本</el-button>
                    <el-button v-if="v.status === 'VALID'" size="small" type="warning"
                      @click="withReason('suspend', '暂停授权（值班经理）')">暂停</el-button>
                    <el-button v-if="v.status === 'SUSPENDED'" size="small" type="success"
                      @click="act('resume', { operator, reason: '值班经理恢复授权' })">恢复</el-button>
                    <el-button v-if="v.status === 'VALID' || v.status === 'SUSPENDED'" size="small" type="danger"
                      @click="withReason('revoke', '撤销授权（值班经理）')">撤销</el-button>
                  </template>
                </template>
              </div>

              <!-- 版本列表（旧版本可查） -->
              <div class="block-title">授权版本（{{ consent.versions.length }} 个，旧版本可查但不再放行）</div>
              <el-collapse v-model="openVersionIds">
                <el-collapse-item v-for="v in consent.versions" :key="v.id"
                  :name="v.id" :title="`v${v.versionNo} · ${v.statusName}`">
                  <template #title>
                    <span :class="{ 'muted': v.status === 'SUPERSEDED' || v.status === 'REVOKED' }">
                      v{{ v.versionNo }}
                      <el-tag size="small" :type="versionStatusType(v.status)" effect="plain" style="margin: 0 8px">
                        {{ v.statusName }}
                      </el-tag>
                      {{ shortDate(v.effectiveFrom) }} ~ {{ shortDate(v.effectiveUntil) }}
                      <span class="muted" style="margin-left: 10px">
                        {{ v.courses.length }} 课程 / {{ v.lanes.length }} 箭道 / {{ v.equipment.length }} 器材 / {{ v.pickups.length }} 接管人
                      </span>
                    </span>
                  </template>
                  <div class="scope-grid">
                    <div>
                      <b>课程</b>
                      <el-tag v-for="c in v.courses" :key="c.courseId" size="small" class="scope-tag">
                        {{ c.courseName }}
                      </el-tag>
                    </div>
                    <div>
                      <b>箭道</b>
                      <el-tag v-for="l in v.lanes" :key="l.laneId" size="small" type="info" class="scope-tag">
                        {{ l.laneNo }}·{{ l.distance }}m
                      </el-tag>
                    </div>
                    <div>
                      <b>器材</b>
                      <el-tag v-for="e in v.equipment" :key="e.equipmentId" size="small" type="warning" class="scope-tag">
                        {{ e.equipCode }}·{{ e.typeName }}
                      </el-tag>
                    </div>
                    <div>
                      <b>接管人</b>
                      <el-tag v-for="p in v.pickups" :key="p.name + p.idNo" size="small" type="success" class="scope-tag">
                        {{ p.name }} {{ p.relationName }} 尾号{{ p.idNo }}
                      </el-tag>
                    </div>
                  </div>
                  <div class="muted version-meta">
                    <span v-if="v.confirmedBy">确认：{{ v.confirmedBy }}（{{ v.confirmedAt }}）</span>
                    <span v-if="v.rejectedBy">拒绝：{{ v.rejectedBy}}（{{ v.rejectReason }}）</span>
                    <span v-if="v.suspendReason">暂停原因：{{ v.suspendReason }}</span>
                    <span v-if="v.revokeReason">撤销原因：{{ v.revokeReason }}</span>
                    <span v-if="v.supersededAt">被取代：{{ v.supersededAt }}</span>
                  </div>
                </el-collapse-item>
              </el-collapse>

              <!-- 轨迹 -->
              <div class="block-title">状态轨迹（前后基线 · 操作人 · 时间 · 原因）</div>
              <el-timeline class="log-line">
                <el-timeline-item v-for="l in consent.logs" :key="l.id"
                  :type="logDotType(l.action)" :timestamp="fullTime(l.createdAt)" placement="top">
                  <b>{{ l.actionName }}</b>
                  <span class="muted">（{{ l.operator }} · {{ l.roleName }}）</span>
                  <div v-if="l.beforeStatusName || l.afterStatusName" class="muted">
                    {{ l.beforeStatusName || '—' }} → {{ l.afterStatusName || '—' }}
                    <span v-if="l.versionNo"> · v{{ l.versionNo }}</span>
                  </div>
                  <div v-if="l.detail" class="log-detail">{{ l.detail }}</div>
                </el-timeline-item>
              </el-timeline>
            </template>
            <el-empty v-else description="选择左侧授权链查看版本与轨迹" />
          </div>
        </div>
      </el-tab-pane>

      <!-- ================= 到馆交接 ================= -->
      <el-tab-pane name="visits">
        <div class="board">
          <div class="panel list-panel">
            <div class="panel-title">
              到馆交接
              <small>未完成 <b class="c-danger">{{ openVisitCount }}</b> 单</small>
              <el-button size="small" type="primary" plain style="margin-left: auto" @click="openCreateVisit">
                建立到馆交接
              </el-button>
            </div>
            <el-radio-group v-model="visitFilter" size="small" style="margin: 0 0 8px 0">
              <el-radio-button value="open">未完成</el-radio-button>
              <el-radio-button value="all">全部</el-radio-button>
            </el-radio-group>
            <el-table :data="visits" height="520" highlight-current-row @current-change="onPickVisit">
              <el-table-column prop="visitNo" label="交接号" width="150">
                <template #default="{ row }"><span class="mono">{{ row.visitNo }}</span></template>
              </el-table-column>
              <el-table-column label="会员" width="100">
                <template #default="{ row }">{{ row.memberName }}</template>
              </el-table-column>
              <el-table-column label="阶段" width="100">
                <template #default="{ row }">
                  <el-tag size="small" :type="visitStatusType(row.status)" effect="dark">{{ row.statusName }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="基线" width="80">
                <template #default="{ row }">v{{ row.baselineVersionNo }}</template>
              </el-table-column>
              <el-table-column label="接管人" min-width="120">
                <template #default="{ row }">
                  <span v-if="row.pickupPersonName">{{ row.pickupPersonName }}（尾号{{ row.pickupPersonIdno }}）</span>
                  <span v-else class="muted">未接管</span>
                </template>
              </el-table-column>
              <el-table-column label="基线状态" width="100">
                <template #default="{ row }">
                  <el-tag v-if="row.baselineFrozen" size="small" type="danger" effect="dark">已冻结</el-tag>
                  <el-tag v-else size="small" type="success" effect="plain">一致</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="panel detail-panel">
            <template v-if="visit">
              <div class="detail-head">
                <div>
                  <div class="detail-title">
                    {{ visit.summary.memberName }} 到馆交接
                    <el-tag size="small" :type="visitStatusType(visit.summary.status)" effect="dark">
                      {{ visit.summary.statusName }}
                    </el-tag>
                    <span class="mono muted">{{ visit.summary.visitNo }}</span>
                  </div>
                  <div class="muted">
                    授权 {{ visit.summary.consentNo }} · 基线 v{{ visit.summary.baselineVersionNo }}
                    · 当前授权状态：{{ visit.currentConsentStatusName }}
                    <span v-if="visit.currentVersionNo">（当前版本 v{{ visit.currentVersionNo }}）</span>
                  </div>
                  <el-alert v-if="visit.summary.baselineFrozen" type="error" :closable="false" show-icon
                    :title="`授权基线已漂移：${visit.summary.freezeReason || ''}`"
                    description="尚未开始的课程 / 箭道 / 器材动作已冻结。已开始的训练事实保留，请工作人员按新基线重新核对，或移除不可用资源后安全交还。"
                    style="margin-top: 8px" />
                </div>
              </div>

              <!-- 阶段动作 -->
              <div class="action-bar">
                <el-button v-if="visit.summary.status === 'PENDING_PICKUP'" size="small" type="primary"
                  @click="openPickup">现场接管</el-button>
                <el-button v-if="visit.summary.status === 'PENDING_PICKUP'" size="small" plain
                  @click="cancelVisit">取消交接</el-button>
                <el-button v-if="visit.summary.status === 'TRAINING'" size="small" type="success"
                  :disabled="visit.summary.baselineFrozen" @click="doRelease">训练前整组放行</el-button>
                <el-button v-if="visit.summary.status === 'TRAINING' && visit.summary.baselineFrozen"
                  size="small" type="warning" @click="doRecheck">按新基线重新核对</el-button>
                <el-button v-if="visit.summary.status === 'TRAINING' && visit.summary.baselineFrozen"
                  size="small" type="warning" plain @click="openTrim">移除不可用资源</el-button>
                <el-button v-if="visit.summary.status === 'TRAINING'" size="small" plain
                  @click="requestReturn">训练结束（待交还）</el-button>
                <el-button v-if="visit.summary.status === 'PENDING_RETURN'" size="small" type="primary"
                  @click="openReturn">安全交还确认</el-button>
              </div>

              <!-- 资源台账 -->
              <div class="block-title">资源放行台账（整组校验，任一不覆盖整体拒绝）</div>
              <el-table :data="visit.resources" size="small" border>
                <el-table-column label="类型" width="70">
                  <template #default="{ row }">{{ row.resourceTypeName }}</template>
                </el-table-column>
                <el-table-column prop="resourceLabel" label="资源" min-width="140" />
                <el-table-column label="状态" width="90">
                  <template #default="{ row }">
                    <el-tag size="small" :type="resourceStatusType(row.status)" effect="dark">{{ row.statusName }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="releaseOperator" label="放行人" width="90" />
                <el-table-column label="放行时间" width="160">
                  <template #default="{ row }">{{ fullTime(row.releasedAt) }}</template>
                </el-table-column>
              </el-table>

              <!-- 交接轨迹 -->
              <div class="block-title">交接轨迹（拦截环节与原因明确展示）</div>
              <el-timeline class="log-line">
                <el-timeline-item v-for="l in visit.logs" :key="l.id" :type="visitLogDotType(l.action)"
                  :timestamp="fullTime(l.createdAt)" placement="top">
                  <b>{{ l.actionName }}</b>
                  <span class="muted">（{{ l.operator }} · {{ l.roleName }}<span v-if="l.stageName"> · {{ l.stageName }}</span>）</span>
                  <div v-if="l.detail" class="log-detail" :class="{ 'block-detail': l.action === 'BLOCK' }">{{ l.detail }}</div>
                </el-timeline-item>
              </el-timeline>
            </template>
            <el-empty v-else description="选择左侧交接单查看基线、放行台账与轨迹" />
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- ================= 弹窗：建立授权链 / 新版本 / 改草稿（共用） ================= -->
    <el-dialog v-model="versionDialog.show" :title="versionDialog.title" width="860px" destroy-on-close>
      <el-form label-width="110px" label-position="right">
        <template v-if="versionDialog.mode === 'create'">
          <el-divider content-position="left">未成年会员与监护人</el-divider>
          <el-form-item label="未成年会员" required>
            <el-select v-model="chainForm.memberId" placeholder="选择未成年会员" style="width: 100%">
              <el-option v-for="m in options.minors || []" :key="m.id"
                :label="`${m.name}（${m.cardNo}${m.birthDate && m.birthDate !== 'null' ? ' · ' + m.birthDate : ''}）`"
                :value="m.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="监护人姓名" required>
            <el-input v-model="chainForm.guardianName" style="width: 200px" />
            <el-select v-model="chainForm.guardianRelation" style="width: 180px; margin-left: 8px">
              <el-option v-for="r in relationOptions" :key="r.code" :label="r.name" :value="r.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="监护人手机" required>
            <el-input v-model="chainForm.guardianPhone" style="width: 240px" />
          </el-form-item>
          <el-form-item label="紧急联系人" required>
            <el-input v-model="chainForm.emergencyContactName" placeholder="姓名" style="width: 200px" />
            <el-input v-model="chainForm.emergencyContactPhone" placeholder="手机号" style="width: 200px; margin-left: 8px" />
          </el-form-item>
          <el-form-item label="必要备注">
            <el-input v-model="chainForm.chainRemark" type="textarea" :rows="2" maxlength="500" show-word-limit
              placeholder="过敏史 / 特殊照护等" />
          </el-form-item>
        </template>
        <el-divider content-position="left">
          {{ versionDialog.mode === 'newVersion' ? `新版本 v${(consent?.summary.currentVersionNo || 0) + 1} 授权范围（旧版本提交后立即停止放行）` : '授权版本范围（作为整体校验）' }}
        </el-divider>
        <el-form-item label="生效 / 终止日" required>
          <el-date-picker v-model="contentForm.effectiveFrom" type="date" value-format="YYYY-MM-DD" placeholder="生效日" style="width: 170px" />
          <span style="margin: 0 6px">~</span>
          <el-date-picker v-model="contentForm.effectiveUntil" type="date" value-format="YYYY-MM-DD" placeholder="终止日" style="width: 170px" />
        </el-form-item>
        <el-form-item label="课程范围" required>
          <el-select v-model="contentForm.courseIds" multiple collapse-tags placeholder="选择允许参与的课程" style="width: 100%">
            <el-option v-for="c in options.courses || []" :key="c.id"
              :label="`${c.name}（${c.coach} · ${c.level}）`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="箭道范围" required>
          <el-select v-model="contentForm.laneIds" multiple collapse-tags placeholder="选择可使用箭道" style="width: 100%">
            <el-option v-for="l in options.lanes || []" :key="l.id" :disabled="l.status === 'LOCKED'"
              :label="`${l.laneNo} · ${l.distance}米 ${l.target}`" :value="l.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="器材范围" required>
          <el-select v-model="contentForm.equipmentIds" multiple collapse-tags placeholder="选择可使用器材" style="width: 100%">
            <el-option v-for="e in options.equipment || []" :key="e.id" :disabled="e.status === 'LOCKED'"
              :label="`${e.code} · ${e.type} · ${e.brand}`" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="接管人名单" required>
          <div style="width: 100%">
            <div v-for="(p, idx) in contentForm.pickups" :key="idx" class="pickup-row">
              <el-input v-model="p.name" placeholder="姓名" style="width: 130px" />
              <el-input v-model="p.idNo" placeholder="证件后四位" style="width: 120px" />
              <el-input v-model="p.phone" placeholder="手机号" style="width: 150px" />
              <el-select v-model="p.relation" style="width: 150px">
                <el-option v-for="r in relationOptions" :key="r.code" :label="r.name" :value="r.code" />
              </el-select>
              <el-button link type="danger" @click="contentForm.pickups.splice(idx, 1)">删除</el-button>
            </div>
            <el-button size="small" plain @click="addPickup">+ 增加接管人</el-button>
          </div>
        </el-form-item>
        <el-form-item label="版本备注">
          <el-input v-model="contentForm.remark" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="versionDialog.show = false">取消</el-button>
        <el-button type="primary" @click="submitVersionDialog">
          {{ versionDialog.mode === 'create' ? '建立草稿' : versionDialog.mode === 'newVersion' ? '建立新版本草稿' : '保存草稿' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 监护人确认 / 拒绝 -->
    <el-dialog v-model="confirmDialog.show" :title="confirmDialog.reject ? '监护人拒绝确认' : '监护人现场确认'" width="520px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px"
        :title="`即将确认的是 v${confirmDialog.expectedVersionNo}；确认后授权生效，重复或过期确认会被拒绝。`" />
      <el-form label-width="92px">
        <el-form-item label="确认人姓名" required>
          <el-input v-model="confirmDialog.confirmerName" />
        </el-form-item>
        <el-form-item label="身份关系" required>
          <el-select v-model="confirmDialog.confirmRelation" style="width: 100%">
            <el-option v-for="r in relationOptions" :key="r.code" :label="r.name" :value="r.code" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!confirmDialog.reject" label="确认备注">
          <el-input v-model="confirmDialog.note" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item v-else label="拒绝原因" required>
          <el-input v-model="confirmDialog.rejectReason" type="textarea" :rows="3" maxlength="500" show-word-limit
            placeholder="拒绝后该版本关闭，前台需按意见发布新版本" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="confirmDialog.show = false">取消</el-button>
        <el-button :type="confirmDialog.reject ? 'danger' : 'success'" @click="submitConfirm">
          {{ confirmDialog.reject ? '拒绝确认' : '确认生效' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 原因输入（暂停/撤销/撤回） -->
    <el-dialog v-model="reasonDialog.show" :title="reasonDialog.title" width="480px">
      <el-input v-model="reasonDialog.reason" type="textarea" :rows="3" maxlength="500" show-word-limit
        :placeholder="reasonDialog.title + '必须填写原因'" />
      <template #footer>
        <el-button @click="reasonDialog.show = false">取消</el-button>
        <el-button type="primary" @click="submitReason">确定</el-button>
      </template>
    </el-dialog>

    <!-- 建立到馆交接 -->
    <el-dialog v-model="createVisitDialog" title="建立到馆交接" width="460px">
      <el-form label-width="92px">
        <el-form-item label="未成年会员" required>
          <el-select v-model="visitMemberId" filterable placeholder="选择未成年会员" style="width: 100%">
            <el-option v-for="m in options.minors || []" :key="m.id"
              :label="`${m.name}（${m.cardNo}）`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="前台操作人">
          <el-input v-model="operator" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisitDialog = false">取消</el-button>
        <el-button type="primary" @click="submitCreateVisit">建单（自动核对当前授权）</el-button>
      </template>
    </el-dialog>

    <!-- 现场接管 -->
    <el-dialog v-model="pickupDialog" title="现场接管：核对授权与接管人身份" width="760px">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 10px"
        title="接管将钉住当前有效授权版本作为现场基线；实际接管人必须在该版本接管人名单内（姓名 + 证件标识一致）。" />
      <el-form label-width="110px">
        <el-form-item label="接管人姓名" required><el-input v-model="personForm.name" style="width: 240px" /></el-form-item>
        <el-form-item label="证件标识" required><el-input v-model="personForm.idNo" placeholder="身份证后四位" style="width: 240px" /></el-form-item>
        <el-form-item label="手机号" required><el-input v-model="personForm.phone" style="width: 240px" /></el-form-item>
        <el-form-item label="关系" required>
          <el-select v-model="personForm.relation" style="width: 240px">
            <el-option v-for="r in relationOptions" :key="r.code" :label="r.name" :value="r.code" />
          </el-select>
        </el-form-item>
        <el-divider content-position="left">本次拟用资源（训练开始前按授权整组校验）</el-divider>
        <el-form-item label="课程">
          <el-select v-model="pickupForm.courseIds" multiple collapse-tags style="width: 100%">
            <el-option v-for="c in options.courses || []" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="箭道">
          <el-select v-model="pickupForm.laneIds" multiple collapse-tags style="width: 100%">
            <el-option v-for="l in options.lanes || []" :key="l.id" :label="l.laneNo" :value="l.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="器材">
          <el-select v-model="pickupForm.equipmentIds" multiple collapse-tags style="width: 100%">
            <el-option v-for="e in options.equipment || []" :key="e.id" :label="e.code" :value="e.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pickupDialog = false">取消</el-button>
        <el-button type="primary" @click="submitPickup">完成接管</el-button>
      </template>
    </el-dialog>

    <!-- 安全交还 -->
    <el-dialog v-model="returnDialog" title="离馆安全交还确认" width="560px">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 10px"
        title="交还人须为基线版本接管人名单内的合格接管人；非名单人员只能由值班经理异常处置（必填经理与原因）。" />
      <el-form label-width="100px">
        <el-form-item label="交还人姓名" required><el-input v-model="personForm.name" /></el-form-item>
        <el-form-item label="证件标识" required><el-input v-model="personForm.idNo" /></el-form-item>
        <el-form-item label="手机号" required><el-input v-model="personForm.phone" /></el-form-item>
        <el-form-item label="关系" required>
          <el-select v-model="personForm.relation" style="width: 100%">
            <el-option v-for="r in relationOptions" :key="r.code" :label="r.name" :value="r.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="异常处置">
          <el-checkbox v-model="returnForm.managerOverride">非名单接管人，值班经理确认放行</el-checkbox>
        </el-form-item>
        <template v-if="returnForm.managerOverride">
          <el-form-item label="值班经理" required><el-input v-model="returnForm.managerName" /></el-form-item>
          <el-form-item label="处置原因" required>
            <el-input v-model="returnForm.overrideReason" type="textarea" :rows="3" maxlength="500" show-word-limit />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="returnDialog = false">取消</el-button>
        <el-button type="primary" @click="submitReturn">完成交还，闭环交接</el-button>
      </template>
    </el-dialog>

    <!-- 移除资源（新基线不覆盖） -->
    <el-dialog v-model="trimDialog" title="移除新基线下未放行的资源" width="560px">
      <el-checkbox-group v-model="trimForm.ids">
        <div v-for="r in trimmableResources" :key="r.id">
          <el-checkbox :value="`${r.resourceType}:${r.resourceId}`">
            {{ r.resourceTypeName }} {{ r.resourceLabel }}（{{ r.statusName }}）
          </el-checkbox>
        </div>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="trimDialog = false">取消</el-button>
        <el-button type="warning" @click="submitTrim">移除并记录异常轨迹</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { guardianApi, shortDate } from '../api'

const roles = [
  { code: 'FRONT', name: '前台' },
  { code: 'COACH', name: '教练' },
  { code: 'KEEPER', name: '器材管理员' },
  { code: 'MANAGER', name: '值班经理' },
  { code: 'GUARDIAN', name: '监护人' }
]
const relationOptions = [
  { code: 'FATHER', name: '父亲' },
  { code: 'MOTHER', name: '母亲' },
  { code: 'GRANDPARENT', name: '祖父母 / 外祖父母' },
  { code: 'OTHER', name: '其他法定监护人' }
]

const operatorRole = ref('FRONT')
const operator = ref('前台-马倩')
const roleName = computed(() => roles.find((r) => r.code === operatorRole.value)?.name || '')
const tab = ref('consents')
const options = ref({})

// ---------------- 授权链 ----------------
const consents = ref([])
const consent = ref(null)
const consentId = ref(null)
const openVersionIds = ref([])

async function loadOptions() {
  options.value = await guardianApi.options()
}
async function loadConsents() {
  consents.value = await guardianApi.listConsents()
}
async function onPickConsent(row) {
  if (!row) return
  consentId.value = row.id
  consent.value = await guardianApi.consentDetail(row.id)
  openVersionIds.value = consent.value.versions.filter((v) => v.id === consent.value.summary.currentVersionId).map((v) => v.id)
}
const currentVersion = computed(() => consent.value?.versions.find((v) => v.id === consent.value.summary.currentVersionId) || null)
const pendingCount = computed(() => consents.value.filter((c) => c.status === 'PENDING_CONFIRM').length)
const validCount = computed(() => consents.value.filter((c) => c.status === 'VALID').length)

// 建链 / 改草稿 / 新版本弹窗
const versionDialog = reactive({ show: false, mode: 'create', title: '' })
const chainForm = reactive({ memberId: null, guardianName: '', guardianPhone: '', guardianRelation: 'FATHER',
  emergencyContactName: '', emergencyContactPhone: '', chainRemark: '' })
const contentForm = reactive({ effectiveFrom: null, effectiveUntil: null, courseIds: [], laneIds: [], equipmentIds: [], pickups: [], remark: '' })

function blankContent(v) {
  return {
    effectiveFrom: v?.effectiveFrom || null,
    effectiveUntil: v?.effectiveUntil || null,
    courseIds: v ? v.courses.map((c) => c.courseId) : [],
    laneIds: v ? v.lanes.map((l) => l.laneId) : [],
    equipmentIds: v ? v.equipment.map((e) => e.equipmentId) : [],
    pickups: v ? v.pickups.map((p) => ({ name: p.name, idNo: p.idNo, phone: p.phone, relation: p.relation })) : [],
    remark: v?.remark || ''
  }
}
function applyContent(v) {
  const c = blankContent(v)
  Object.assign(contentForm, c)
}
function addPickup() {
  contentForm.pickups.push({ name: '', idNo: '', phone: '', relation: 'FATHER' })
}
function openCreate() {
  versionDialog.mode = 'create'
  versionDialog.title = '建立监护授权链（首版为草稿）'
  Object.assign(chainForm, { memberId: null, guardianName: '', guardianPhone: '', guardianRelation: 'FATHER',
    emergencyContactName: '', emergencyContactPhone: '', chainRemark: '' })
  applyContent(null)
  addPickup()
  versionDialog.show = true
}
function openEditDraft(v) {
  versionDialog.mode = 'editDraft'
  versionDialog.title = `修改草稿 v${v.versionNo}（提交后要改内容必须发新版本）`
  applyContent(v)
  versionDialog.show = true
}
function openNewVersion(v) {
  versionDialog.mode = 'newVersion'
  versionDialog.title = `基于 v${v.versionNo} 发布新版本`
  applyContent(v)
  versionDialog.show = true
}
async function submitVersionDialog() {
  const version = { ...contentForm }
  try {
    if (versionDialog.mode === 'create') {
      await guardianApi.createConsent({ ...chainForm, version, operator: operator.value })
      ElMessage.success('授权链已建立')
    } else if (versionDialog.mode === 'editDraft') {
      await guardianApi.updateDraft(consentId.value, { version, operator: operator.value })
    } else {
      await guardianApi.newVersion(consentId.value, { version, operator: operator.value })
    }
    versionDialog.show = false
    await loadConsents()
    if (consentId.value) consent.value = await guardianApi.consentDetail(consentId.value)
  } catch (e) { /* 拦截提示已由拦截器弹出 */ }
}

async function act(action, payload) {
  try {
    await guardianApi[action](consentId.value, payload)
    await loadConsents()
    consent.value = await guardianApi.consentDetail(consentId.value)
  } catch (e) { /* ignore */ }
}
function withReason(action, title) {
  return ElMessageBox.prompt(`${title}，请填写原因`, title, {
    confirmButtonText: '确定', cancelButtonText: '取消', inputType: 'textarea', inputValidator: (v) => !!v?.trim() || '原因必填'
  }).then(({ value }) => act(action, { operator: operator.value, reason: value })).catch(() => {})
}

// 确认 / 拒绝弹窗
const confirmDialog = reactive({ show: false, reject: false, confirmerName: '', confirmRelation: 'FATHER',
  note: '', rejectReason: '', expectedVersionNo: null })
function openConfirm(reject) {
  Object.assign(confirmDialog, {
    show: true, reject, confirmerName: consent.value.summary.guardianName,
    confirmRelation: consent.value.summary.guardianRelation, note: '', rejectReason: '',
    expectedVersionNo: consent.value.summary.currentVersionNo
  })
}
async function submitConfirm() {
  try {
    await guardianApi.confirm(consentId.value, {
      confirmerName: confirmDialog.confirmerName,
      confirmRelation: confirmDialog.confirmRelation,
      expectedVersionNo: confirmDialog.expectedVersionNo,
      note: confirmDialog.note,
      reject: confirmDialog.reject,
      rejectReason: confirmDialog.rejectReason
    })
    confirmDialog.show = false
    await loadConsents()
    consent.value = await guardianApi.consentDetail(consentId.value)
  } catch (e) { /* ignore */ }
}

// 原因弹窗（暂停/撤销/撤回）
const reasonDialog = reactive({ show: false, action: '', title: '', reason: '' })
function openReasonDialog(action, title) {
  reasonDialog.action = action
  reasonDialog.title = title
  reasonDialog.reason = ''
  reasonDialog.show = true
}
async function submitReason() {
  if (!reasonDialog.reason.trim()) return ElMessage.warning('请填写原因')
  await act(reasonDialog.action, { operator: operator.value, reason: reasonDialog.reason })
  reasonDialog.show = false
}

// ---------------- 到馆交接 ----------------
const visits = ref([])
const visit = ref(null)
const visitId = ref(null)
const visitFilter = ref('open')
const createVisitDialog = ref(false)
const visitMemberId = ref(null)
const pickupDialog = ref(false)
const returnDialog = ref(false)
const trimDialog = ref(false)
const personForm = reactive({ name: '', idNo: '', phone: '', relation: 'FATHER' })
const pickupForm = reactive({ courseIds: [], laneIds: [], equipmentIds: [] })
const returnForm = reactive({ managerOverride: false, managerName: '', overrideReason: '' })
const trimForm = reactive({ ids: [] })

async function loadVisits() {
  visits.value = await guardianApi.listVisits({ open: visitFilter.value === 'open' })
}
async function onPickVisit(row) {
  if (!row) return
  visitId.value = row.id
  visit.value = await guardianApi.visitDetail(row.id)
}
const openVisitCount = computed(() => visits.value.filter((v) => v.status !== 'RETURNED' && v.status !== 'CANCELED').length)
const trimmableResources = computed(() => (visit.value?.resources || []).filter((r) => r.status !== 'RELEASED'))

function resetPerson() {
  Object.assign(personForm, { name: '', idNo: '', phone: '', relation: 'FATHER' })
}
function openCreateVisit() {
  visitMemberId.value = null
  createVisitDialog.value = true
}
async function submitCreateVisit() {
  try {
    const v = await guardianApi.createVisit({ memberId: visitMemberId.value, operator: operator.value })
    createVisitDialog.value = false
    await loadVisits()
    visitId.value = v.summary.id
    visit.value = v
  } catch (e) { /* ignore */ }
}
function openPickup() {
  resetPerson()
  Object.assign(pickupForm, { courseIds: [], laneIds: [], equipmentIds: [] })
  pickupDialog.value = true
}
async function submitPickup() {
  try {
    const v = await guardianApi.pickup(visitId.value, { operator: operator.value, person: { ...personForm }, ...pickupForm })
    pickupDialog.value = false
    visit.value = v
    await loadVisits()
  } catch (e) { /* ignore */ }
}
async function doRelease() {
  try {
    visit.value = await guardianApi.release(visitId.value, { operator: operator.value })
    await loadVisits()
  } catch (e) {
    visit.value = await guardianApi.visitDetail(visitId.value)
  }
}
async function doRecheck() {
  try {
    visit.value = await guardianApi.recheck(visitId.value, { operator: operator.value })
  } catch (e) { /* ignore */ }
}
function openTrim() {
  trimForm.ids = []
  trimDialog.value = true
}
async function submitTrim() {
  const payload = { operator: operator.value, courseIds: [], laneIds: [], equipmentIds: [] }
  trimForm.ids.forEach((key) => {
    const [type, id] = key.split(':')
    const num = Number(id)
    if (type === 'COURSE') payload.courseIds.push(num)
    if (type === 'LANE') payload.laneIds.push(num)
    if (type === 'EQUIPMENT') payload.equipmentIds.push(num)
  })
  try {
    visit.value = await guardianApi.trimResources(visitId.value, payload)
    trimDialog.value = false
  } catch (e) { /* ignore */ }
}
async function requestReturn() {
  try {
    visit.value = await guardianApi.requestReturn(visitId.value, { operator: operator.value })
    await loadVisits()
  } catch (e) { /* ignore */ }
}
function openReturn() {
  resetPerson()
  Object.assign(returnForm, { managerOverride: false, managerName: '', overrideReason: '' })
  returnDialog.value = true
}
async function submitReturn() {
  try {
    visit.value = await guardianApi.completeReturn(visitId.value, {
      operator: operator.value, person: { ...personForm }, ...returnForm
    })
    returnDialog.value = false
    await loadVisits()
  } catch (e) { /* ignore */ }
}
async function cancelVisit() {
  try {
    const { value } = await ElMessageBox.prompt('取消未实际接管的交接单，必须填写原因', '取消交接', {
      confirmButtonText: '确认取消', cancelButtonText: '返回', inputType: 'textarea', inputValidator: (v) => !!v?.trim() || '原因必填'
    })
    visit.value = await guardianApi.cancelVisit(visitId.value, { operator: operator.value, reason: value })
    await loadVisits()
  } catch (e) { /* ignore */ }
}

// ---------------- 展示辅助 ----------------
function fullTime(v) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : ''
}
function consentStatusType(s) {
  return { VALID: 'success', PENDING_CONFIRM: 'warning', DRAFT: 'info', SUSPENDED: 'warning', REVOKED: 'danger' }[s] || 'info'
}
function versionStatusType(s) {
  return { VALID: 'success', PENDING_CONFIRM: 'warning', DRAFT: 'info', SUSPENDED: 'warning',
    REVOKED: 'danger', SUPERSEDED: 'info', REJECTED: 'danger' }[s] || 'info'
}
function visitStatusType(s) {
  return { PENDING_PICKUP: 'info', TRAINING: 'success', PENDING_RETURN: 'warning',
    RETURNED: '', CANCELED: 'info' }[s] || 'info'
}
function resourceStatusType(s) {
  return { PENDING: 'info', RELEASED: 'success', FROZEN: 'danger', REJECTED: 'danger' }[s] || 'info'
}
function logDotType(a) {
  if (a === 'CONFIRM' || a === 'RESUME' || a === 'SUPERSEDE') return 'primary'
  if (a === 'SUSPEND' || a === 'REVOKE' || a === 'REJECT') return 'warning'
  return undefined
}
function visitLogDotType(a) {
  if (a === 'BLOCK' || a === 'FREEZE') return 'danger'
  if (a === 'EXCEPTION') return 'warning'
  if (a === 'RELEASE' || a === 'RETURN' || a === 'PICKUP') return 'success'
  return undefined
}

onMounted(async () => {
  await loadOptions()
  await Promise.all([loadConsents(), loadVisits()])
})

// 切换到交接页签 / 切换过滤时刷新在途数据，避免别的端操作后看到旧阶段
watch(tab, (value) => {
  if (value === 'visits') loadVisits()
})
watch(visitFilter, () => {
  if (tab.value === 'visits') loadVisits()
})
</script>

<style scoped>
.page { padding: 16px 20px; }
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.page-title { font-size: 18px; margin: 0; }
.page-title em { font-style: normal; font-size: 12px; color: var(--ink-mute); margin-left: 10px; font-weight: normal; }
.rule-tip { background: #f3f8f7; border: 1px solid #d5e6e2; border-radius: 8px; padding: 10px 12px; font-size: 12.5px; color: #44635d; line-height: 1.7; margin-bottom: 12px; }
.board { display: grid; grid-template-columns: minmax(420px, 1fr) minmax(460px, 1.2fr); gap: 12px; }
.panel { background: #fff; border: 1px solid var(--line); border-radius: 10px; padding: 12px; }
.panel-title { display: flex; align-items: center; gap: 8px; font-weight: 700; margin-bottom: 10px; }
.panel-title small { font-weight: 400; color: var(--ink-mute); }
.detail-head { border-bottom: 1px dashed var(--line); padding-bottom: 8px; margin-bottom: 8px; }
.detail-title { font-size: 15px; font-weight: 700; display: flex; gap: 8px; align-items: center; }
.action-bar { display: flex; flex-wrap: wrap; gap: 6px; margin: 8px 0 12px; }
.block-title { font-weight: 700; font-size: 13px; margin: 14px 0 8px; border-left: 3px solid var(--el-color-primary); padding-left: 8px; }
.scope-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 16px; font-size: 12.5px; }
.scope-tag { margin: 2px 4px 2px 0; }
.version-meta { display: flex; flex-direction: column; gap: 2px; margin-top: 6px; font-size: 12px; }
.log-line { margin-top: 4px; padding-left: 4px; }
.log-detail { font-size: 12.5px; color: #4a5a56; }
.block-detail { color: var(--el-color-danger); font-weight: 600; }
.muted { color: var(--ink-mute); font-size: 12px; }
.mono { font-family: ui-monospace, Menlo, monospace; font-size: 12px; }
.c-danger { color: var(--el-color-danger); }
.c-warn { color: var(--el-color-warning); }
.g-tabs { background: transparent; }
.pickup-row { display: flex; gap: 6px; margin-bottom: 6px; align-items: center; }
</style>
