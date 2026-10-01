<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">
        失物保管与认领交接台
        <em>登记即事实 · 封签保管链 · 最小信息审核 · 双人现场交还 · 逾期分流处置</em>
      </h2>
      <div class="page-tools">
        <el-radio-group v-model="role" size="small">
          <el-radio-button v-for="r in roles" :key="r.code" :value="r.code">{{ r.name }}</el-radio-button>
        </el-radio-group>
        <el-input v-model="operator" placeholder="当前操作人姓名" style="width: 150px" />
      </div>
    </div>

    <div class="rule-tip">
      物品在箭道 / 课程区 / 公共区域被发现后，由发现人登记类别、位置、时间、外观与现场照片，值班前台生成封签完成首次交接；
      后续查看、转移、开封、认领、交还、异常、处置全部沿用<b>同一条追加式交接链</b>，不能用修改原登记抹掉前序事实。
      公示给认领人的只有<b>模糊类别、发现日期、馆内区域</b>；认领审核必须由当前保管人与另一名工作人员<b>双人交还</b>，
      异常须值班经理处置且不能跳过双人交还。当前角色：<b>{{ roleName }}</b>。
    </div>

    <el-tabs v-model="tab" class="l-tabs">
      <!-- ============ 公示板（认领人） ============ -->
      <el-tab-pane name="public">
        <div class="panel">
          <div class="panel-title">
            失物招领公示
            <small>仅展示模糊类别 · 发现日期 · 馆内区域；如其中有您的物品，请携带本人证件到前台描述细节后发起认领</small>
            <el-button size="small" plain style="margin-left: auto" @click="loadPublic">刷新</el-button>
          </div>
          <el-table :data="publicItems" stripe>
            <el-table-column prop="itemNo" label="公示编号" width="170">
              <template #default="{ row }"><span class="mono">{{ row.itemNo }}</span></template>
            </el-table-column>
            <el-table-column prop="publicCategoryName" label="物品类别（模糊）" width="160" />
            <el-table-column prop="foundDate" label="发现日期" width="140" />
            <el-table-column prop="areaName" label="馆内区域" min-width="160" />
          </el-table>
          <el-empty v-if="!publicItems.length" description="当前没有在公示的失物" />
        </div>
      </el-tab-pane>

      <!-- ============ 失物登记 ============ -->
      <el-tab-pane name="register">
        <div class="board">
          <div class="panel">
            <div class="panel-title">失物登记（发现人登记 → 前台封签）
              <small>登记内容写入后不可修改</small>
            </div>
            <el-form label-width="110px" :disabled="role !== 'FRONT'">
              <el-form-item label="物品类别" required>
                <el-select v-model="regForm.category" placeholder="选择类别" style="width: 220px">
                  <el-option v-for="c in options.categories || []" :key="c.code" :label="c.name" :value="c.code" />
                </el-select>
              </el-form-item>
              <el-form-item label="发现位置" required>
                <el-radio-group v-model="regForm.foundLocationType">
                  <el-radio value="LANE">箭道</el-radio>
                  <el-radio value="COURSE">课程区</el-radio>
                  <el-radio value="PUBLIC">公共区域</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item v-if="regForm.foundLocationType === 'LANE'" label="具体箭道" required>
                <el-select v-model="regForm.foundLaneId" placeholder="选择箭道" style="width: 220px">
                  <el-option v-for="l in options.lanes || []" :key="l.id" :label="l.laneNo" :value="l.id" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="regForm.foundLocationType === 'COURSE'" label="具体课程" required>
                <el-select v-model="regForm.foundCourseId" placeholder="选择课程" style="width: 300px">
                  <el-option v-for="c in options.courses || []" :key="c.id" :label="c.name" :value="c.id" />
                </el-select>
              </el-form-item>
              <el-form-item :label="regForm.foundLocationType === 'PUBLIC' ? '公共区域' : '区域备注'" required>
                <el-input v-model="regForm.foundArea" :placeholder="regForm.foundLocationType === 'PUBLIC' ? '如：前台休息区 / 更衣室 / 馆门口' : '位置补充说明'" style="width: 320px" />
              </el-form-item>
              <el-form-item label="发现时间" required>
                <el-date-picker v-model="regForm.foundAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
                  placeholder="选择发现时间" style="width: 240px" />
              </el-form-item>
              <el-form-item label="外观特征" required>
                <el-input v-model="regForm.appearance" type="textarea" :rows="3" maxlength="500" show-word-limit
                  placeholder="颜色 / 品牌 / 明显标记 / 内含物品等（内部核验用，不对认领人公示）" />
              </el-form-item>
              <el-form-item label="现场照片">
                <el-input v-model="regForm.photoRefs" type="textarea" :rows="2"
                  placeholder="照片引用，多张以英文逗号分隔（仅工作人员在权限内查看）" />
              </el-form-item>
              <el-form-item label="特殊标记">
                <el-checkbox v-model="regForm.highValueFlag">高价值物品（保管 90 天 · 逾期只能返还公安）</el-checkbox>
                <el-checkbox v-model="regForm.identityFlag">带身份信息物品（逾期只能返还公安）</el-checkbox>
              </el-form-item>
              <el-form-item label="发现人" required>
                <el-input v-model="regForm.finderName" placeholder="姓名" style="width: 160px" />
                <el-radio-group v-model="regForm.finderRole" style="margin-left: 10px">
                  <el-radio value="MEMBER">会员</el-radio>
                  <el-radio value="STAFF">工作人员</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="发现人联系方式">
                <el-input v-model="regForm.finderContact" placeholder="选填（仅工作人员可见）" style="width: 240px" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="submitRegister">登记失物</el-button>
                <span class="muted">登记后状态为「待首次封存」，请在右侧生成封签</span>
              </el-form-item>
            </el-form>
            <el-alert v-if="role !== 'FRONT'" type="warning" :closable="false" show-icon
              title="失物登记由值班前台办理，请切换到前台角色" style="margin-top: 8px" />
          </div>

          <div class="panel">
            <div class="panel-title">待首次封存
              <small>值班前台生成封签完成首次交接</small>
            </div>
            <el-table :data="registeredRows" highlight-current-row @current-change="onPick" height="560">
              <el-table-column prop="itemNo" label="失物编号" width="150">
                <template #default="{ row }"><span class="mono">{{ row.itemNo }}</span></template>
              </el-table-column>
              <el-table-column prop="categoryName" label="类别" width="100" />
              <el-table-column prop="foundArea" label="发现区域" min-width="140" />
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <el-tag size="small" type="info" effect="dark">{{ row.statusName }}</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <!-- ============ 工作台：保管链 / 认领 / 审核 / 交还 / 处置（按角色出现动作） ============ -->
      <el-tab-pane v-for="t in workTabs" :key="t.key" :name="t.key">
        <div class="board">
          <div class="panel list-panel">
            <div class="panel-title">{{ t.title }}
              <small>{{ t.hint }}</small>
              <el-button size="small" plain style="margin-left: auto" @click="loadWork(t.scope)">刷新</el-button>
            </div>
            <el-radio-group v-model="workScope" size="small" style="margin-bottom: 8px" @change="loadWork()">
              <el-radio-button value="custody">保管中</el-radio-button>
              <el-radio-button value="return">待交还/异常</el-radio-button>
              <el-radio-button value="overdue">待处置</el-radio-button>
              <el-radio-button value="all">全部</el-radio-button>
            </el-radio-group>
            <el-table :data="workRows" height="530" highlight-current-row @current-change="onPick">
              <el-table-column prop="itemNo" label="编号" width="140">
                <template #default="{ row }"><span class="mono">{{ row.itemNo }}</span></template>
              </el-table-column>
              <el-table-column prop="categoryName" label="类别" width="90" />
              <el-table-column prop="foundArea" label="区域" min-width="120" show-overflow-tooltip />
              <el-table-column label="标记" width="120">
                <template #default="{ row }">
                  <el-tag v-if="row.highValueFlag" size="small" type="danger" effect="plain">高值</el-tag>
                  <el-tag v-if="row.identityFlag" size="small" type="warning" effect="plain">证件</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag size="small" :type="itemStatusType(row.status)" effect="dark">{{ row.statusName }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="封签" width="80">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.sealState === 'SEALED' ? 'success' : 'danger'" effect="plain">
                    {{ row.sealStateName }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="currentCustodian" label="保管人" width="100" />
            </el-table>
          </div>

          <div class="panel detail-panel">
            <template v-if="detail">
              <div class="detail-head">
                <div class="detail-title">
                  <span class="mono">{{ detail.itemNo }}</span>
                  <el-tag size="small" :type="itemStatusType(detail.status)" effect="dark">{{ detail.statusName }}</el-tag>
                  <el-tag size="small" :type="detail.sealState === 'SEALED' ? 'success' : 'danger'" effect="plain">
                    {{ detail.sealStateName }}
                  </el-tag>
                  <el-tag v-if="detail.highValueFlag" size="small" type="danger" effect="plain">高价值</el-tag>
                  <el-tag v-if="detail.identityFlag" size="small" type="warning" effect="plain">带证件</el-tag>
                  <el-tag v-if="detail.overdue" size="small" type="warning" effect="dark">已逾期</el-tag>
                </div>
                <div class="muted">
                  {{ detail.categoryName }} · 发现于 {{ detail.foundLocationTypeName }}
                  <span v-if="detail.foundLaneNo"> {{ detail.foundLaneNo }}</span>
                  <span v-if="detail.foundCourseName"> {{ detail.foundCourseName }}</span>
                  {{ detail.foundArea }} · {{ fullTime(detail.foundAt) }}
                  · 保管期限至 {{ shortDate(detail.custodyDeadline) }}
                </div>
              </div>

              <!-- 当前角色可执行动作（只渲染这些按钮） -->
              <div class="action-bar">
                <el-button v-if="can('FIRST_SEAL')" size="small" type="primary" @click="openFirstSeal">生成封签首次交接</el-button>
                <el-button v-if="can('CREATE_CLAIM')" size="small" type="primary" @click="openClaim">录入认领申报</el-button>
                <el-button v-if="can('SUPPLEMENT')" size="small" type="warning" @click="openSupplement">录入补证材料</el-button>
                <el-button v-if="can('REVIEW')" size="small" type="success" @click="openReview">值班经理审核</el-button>
                <el-button v-if="can('INSPECT')" size="small" @click="openInspect">查看（开封登记）</el-button>
                <el-button v-if="can('RESEAL')" size="small" type="warning" @click="openReseal">重新封存</el-button>
                <el-button v-if="can('TRANSFER')" size="small" @click="openTransfer">保管转移</el-button>
                <el-button v-if="can('START_HANDOVER')" size="small" type="success" @click="doStartHandover">发起现场交接</el-button>
                <el-button v-if="can('COMPLETE_HANDOVER')" size="small" type="success" @click="openHandover">双人现场交还</el-button>
                <el-button v-if="can('REPORT_EXCEPTION')" size="small" type="danger" plain @click="openException">登记异常交接</el-button>
                <el-button v-if="can('RESOLVE_EXCEPTION')" size="small" type="danger" @click="openResolve">值班经理异常处置</el-button>
                <el-button v-if="can('MARK_OVERDUE')" size="small" type="warning" plain @click="doMarkOverdue">扫描逾期转待处置</el-button>
                <el-button v-if="can('DISPOSE')" size="small" type="danger" @click="openDispose">登记逾期处置</el-button>
              </div>

              <!-- 登记事实（教练不可见外观 / 联系方式 / 照片） -->
              <div class="block-title">登记事实（不可修改）</div>
              <div class="fact-grid">
                <div><b>外观特征：</b>{{ detail.appearance || '（当前角色无权查看）' }}</div>
                <div><b>发现人：</b>{{ detail.finderName }}（{{ detail.finderRole === 'MEMBER' ? '会员' : '工作人员' }}）
                  <span v-if="detail.finderContact"> · {{ detail.finderContact }}</span>
                </div>
                <div><b>现场照片：</b>
                  <template v-if="detail.photoRefs && detail.photoRefs.length">
                    <el-tag v-for="(p, i) in detail.photoRefs" :key="i" size="small" type="danger" effect="dark" class="scope-tag">{{ p }}</el-tag>
                  </template>
                  <span v-else-if="detail.photoUnlocked">无照片引用</span>
                  <el-tag v-else size="small" type="info" effect="plain">仅经理审核时显式调阅可见</el-tag>
                </div>
              </div>

              <!-- 认领审核信息 -->
              <template v-if="detail.activeClaim">
                <div class="block-title">
                  当前在办认领 <span class="mono muted">{{ detail.activeClaim.claimNo }}</span>
                  <el-tag size="small" :type="claimStatusType(detail.activeClaim.status)" effect="dark" style="margin-left:6px">
                    {{ detail.activeClaim.statusName }}
                  </el-tag>
                </div>
                <div class="fact-grid">
                  <div><b>认领人：</b>{{ detail.activeClaim.claimantName }}
                    （{{ detail.activeClaim.claimantTypeName }}<span v-if="detail.activeClaim.claimantPhone"> · {{ detail.activeClaim.claimantPhone }}</span>）
                  </div>
                  <div v-if="detail.activeClaim.claimDesc"><b>物品描述：</b>{{ detail.activeClaim.claimDesc }}</div>
                  <div v-else><el-tag size="small" type="info" effect="plain">物品描述：当前角色无权查看</el-tag></div>
                  <div><b>遗失时间：</b>{{ fullTime(detail.activeClaim.lostTimeFrom) }} ~ {{ fullTime(detail.activeClaim.lostTimeTo) }}</div>
                  <div><b>可能出现：</b>
                    {{ detail.activeClaim.possibleLaneNo || detail.activeClaim.possibleCourseName || detail.activeClaim.possibleArea || '—' }}
                  </div>
                  <div v-if="detail.activeClaim.privacyFeatures">
                    <b>隐私特征：</b><el-tag size="small" type="danger" effect="plain">{{ detail.activeClaim.privacyFeatures }}</el-tag>
                  </div>
                  <div v-else><el-tag size="small" type="info" effect="plain">隐私特征：仅值班经理审核可见</el-tag></div>
                  <div v-if="detail.activeClaim.supplementary"><b>补证材料：</b>{{ detail.activeClaim.supplementary }}</div>
                </div>

                <div v-if="detail.activeClaim.reviews && detail.activeClaim.reviews.length" class="block-title">
                  分项审核结论（每轮保留）
                </div>
                <el-table v-if="detail.activeClaim.reviews && detail.activeClaim.reviews.length"
                  :data="detail.activeClaim.reviews" size="small" border>
                  <el-table-column prop="reviewRound" label="轮次" width="55" />
                  <el-table-column label="特征匹配" width="90">
                    <template #default="{ row }"><el-tag size="small" :type="resultType(row.featureResult)" effect="dark">{{ row.featureResultName }}</el-tag></template>
                  </el-table-column>
                  <el-table-column label="时间地点" width="90">
                    <template #default="{ row }"><el-tag size="small" :type="resultType(row.timeplaceResult)" effect="dark">{{ row.timeplaceResultName }}</el-tag></template>
                  </el-table-column>
                  <el-table-column label="补充证明" width="90">
                    <template #default="{ row }"><el-tag size="small" :type="resultType(row.supportResult)" effect="plain">{{ row.supportResultName }}</el-tag></template>
                  </el-table-column>
                  <el-table-column label="终判" width="90">
                    <template #default="{ row }"><el-tag size="small" :type="decisionType(row.decision)" effect="dark">{{ row.decisionName }}</el-tag></template>
                  </el-table-column>
                  <el-table-column prop="reviewNote" label="审核意见" min-width="160" show-overflow-tooltip />
                  <el-table-column prop="reviewedBy" label="经理" width="90" />
                </el-table>
              </template>

              <!-- 历史认领（终态可查，不能被改回已交还） -->
              <template v-if="detail.claims && detail.claims.length > 1">
                <div class="block-title">本物品全部认领单（{{ detail.claims.length }} 笔，驳回 / 终止为终态）</div>
                <el-table :data="detail.claims" size="small" border>
                  <el-table-column prop="claimNo" label="认领单号" width="150">
                    <template #default="{ row }"><span class="mono">{{ row.claimNo }}</span></template>
                  </el-table-column>
                  <el-table-column label="认领人" min-width="120">
                    <template #default="{ row }">{{ row.claimantName }}（{{ row.claimantTypeName }}）</template>
                  </el-table-column>
                  <el-table-column label="状态" width="100">
                    <template #default="{ row }">
                      <el-tag size="small" :type="claimStatusType(row.status)" effect="dark">{{ row.statusName }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="审核轮次" width="80">
                    <template #default="{ row }">{{ (row.reviews || []).length }}</template>
                  </el-table-column>
                </el-table>
              </template>

              <!-- 现场交接 -->
              <template v-if="detail.handover">
                <div class="block-title">现场认领交接</div>
                <div class="fact-grid">
                  <div><b>状态：</b><el-tag size="small" :type="handoverType(detail.handover.status)" effect="dark">{{ detail.handover.statusName }}</el-tag></div>
                  <div><b>保管人：</b>{{ detail.handover.custodian }}</div>
                  <div v-if="detail.handover.witness"><b>见证工作人员：</b>{{ detail.handover.witness }}</div>
                  <div v-if="detail.handover.receiverName"><b>实际领取人：</b>{{ detail.handover.receiverName }}
                    （{{ detail.handover.receiverIdTypeName }} · {{ detail.handover.receiverIdNo }}）</div>
                  <div v-if="detail.handover.handoverAt"><b>交还时间：</b>{{ fullTime(detail.handover.handoverAt) }}</div>
                  <div v-if="detail.handover.exceptionReason">
                    <b>异常：</b><el-tag size="small" type="danger" effect="dark">{{ detail.handover.exceptionReasonName }}</el-tag>
                    {{ detail.handover.exceptionDetail }}
                  </div>
                  <div v-if="detail.handover.resolution">
                    <b>经理处置：</b><el-tag size="small" type="warning" effect="dark">{{ detail.handover.resolutionName }}</el-tag>
                    {{ detail.handover.resolutionDetail }}
                  </div>
                </div>
              </template>

              <!-- 处置凭证 -->
              <template v-if="detail.disposal">
                <div class="block-title">逾期处置凭证</div>
                <div class="fact-grid">
                  <div><b>结果：</b>{{ detail.disposal.resultName }}</div>
                  <div><b>接收方：</b>{{ detail.disposal.receiverName }}
                    <span v-if="detail.disposal.receiverContact"> · {{ detail.disposal.receiverContact }}</span>
                  </div>
                  <div><b>凭证编号：</b><span class="mono">{{ detail.disposal.receiptNo }}</span></div>
                  <div v-if="detail.disposal.receiptNote"><b>凭证说明：</b>{{ detail.disposal.receiptNote }}</div>
                </div>
              </template>

              <!-- 封签序列 -->
              <div class="block-title">封签保管链（{{ detail.seals.length }} 枚，开封原因逐枚留痕）</div>
              <el-timeline class="log-line">
                <el-timeline-item v-for="s in [...detail.seals].reverse()" :key="s.id"
                  :type="s.sealState === 'SEALED' ? 'success' : 'danger'"
                  :timestamp="`第 ${s.sealSeq} 枚 · ${s.sealNo}`" placement="top">
                  <el-tag size="small" :type="s.sealState === 'SEALED' ? 'success' : 'danger'" effect="plain">{{ s.sealStateName }}</el-tag>
                  <b style="margin: 0 6px">{{ s.custodian }}（{{ roleOf(s.custodianRole) }}）</b>
                  <span class="muted">{{ s.storageAreaName }} · 封存 {{ fullTime(s.sealedAt) }}</span>
                  <div v-if="s.breakReason" class="log-detail">
                    开封：{{ s.breakReasonName }} · {{ s.brokenBy }} · {{ fullTime(s.brokenAt) }}
                    <span v-if="s.resealNote"> · {{ s.resealNote }}</span>
                  </div>
                </el-timeline-item>
              </el-timeline>

              <!-- 完整交接链 -->
              <div class="block-title">交接链（追加式，全程留痕，不可抹除）</div>
              <el-timeline class="log-line">
                <el-timeline-item v-for="l in [...detail.logs].reverse()" :key="l.id"
                  :type="logDotType(l.action)" :timestamp="fullTime(l.createdAt)" placement="top">
                  <b>{{ l.actionName }}</b>
                  <span class="muted">（{{ l.operator }} · {{ l.roleName }}<span v-if="l.beforeStatusName || l.afterStatusName"> · {{ l.beforeStatusName || '—' }} → {{ l.afterStatusName || '—' }}</span>）</span>
                  <div v-if="l.detail" class="log-detail">{{ l.detail }}</div>
                </el-timeline-item>
              </el-timeline>
            </template>
            <el-empty v-else description="选择左侧物品查看保管链、认领审核、交接与处置" />
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- ===== 弹窗：首次封存 ===== -->
    <el-dialog v-model="firstSealDlg" title="生成失物封签 · 首次交接" width="520px">
      <el-form label-width="92px">
        <el-form-item label="保管人" required><el-input v-model="sealForm.custodian" :placeholder="operator" /></el-form-item>
        <el-form-item label="保管人岗位" required>
          <el-radio-group v-model="sealForm.custodianRole">
            <el-radio value="FRONT">前台</el-radio><el-radio value="KEEPER">保管员</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="存放区域" required>
          <el-select v-model="sealForm.storageArea" style="width: 100%">
            <el-option v-for="a in options.storageAreas || []" :key="a.code" :label="a.name + (a.code === 'SAFE' ? '（高价值物品）' : '')" :value="a.code" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="firstSealDlg = false">取消</el-button>
        <el-button type="primary" @click="submitFirstSeal">生成封签并交接</el-button>
      </template>
    </el-dialog>

    <!-- ===== 查看开封 / 重封存 / 转移 ===== -->
    <el-dialog v-model="inspectDlg" title="工作人员查看（登记开封原因）" width="480px">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 10px"
        title="查看将开封当前封签（原因：查看），封签随即作废，查看完成后必须重新封存。" />
      <el-input v-model="inspectForm.reason" type="textarea" :rows="3" maxlength="500" show-word-limit
        placeholder="查看原因，如：认领审核需要核对外观" />
      <template #footer>
        <el-button @click="inspectDlg = false">取消</el-button>
        <el-button type="warning" @click="submitInspect">确认开封查看</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resealDlg" title="重新封存（另发新签）" width="520px">
      <el-form label-width="92px">
        <el-form-item label="保管人" required><el-input v-model="sealForm.custodian" /></el-form-item>
        <el-form-item label="岗位" required>
          <el-radio-group v-model="sealForm.custodianRole">
            <el-radio value="FRONT">前台</el-radio><el-radio value="KEEPER">保管员</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="存放区域" required>
          <el-select v-model="sealForm.storageArea" style="width: 100%">
            <el-option v-for="a in options.storageAreas || []" :key="a.code" :label="a.name" :value="a.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="核验说明" required>
          <el-input v-model="resealForm.note" type="textarea" :rows="3" maxlength="500" show-word-limit
            placeholder="开封期间核验情况、物品是否与登记一致" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resealDlg = false">取消</el-button>
        <el-button type="primary" @click="submitReseal">生成新封签</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="transferDlg" title="保管转移（旧签开封 · 新保管人重封存）" width="520px">
      <el-form label-width="100px">
        <el-form-item label="新保管人" required><el-input v-model="transferForm.toCustodian" /></el-form-item>
        <el-form-item label="新保管岗位" required>
          <el-radio-group v-model="transferForm.toCustodianRole">
            <el-radio value="FRONT">前台</el-radio><el-radio value="KEEPER">保管员</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="新存放区域" required>
          <el-select v-model="transferForm.toStorageArea" style="width: 100%">
            <el-option v-for="a in options.storageAreas || []" :key="a.code" :label="a.name" :value="a.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="转移核验说明" required>
          <el-input v-model="transferForm.note" type="textarea" :rows="3" maxlength="500" show-word-limit
            placeholder="转移原因；交接时实物与登记核对情况" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferDlg = false">取消</el-button>
        <el-button type="primary" @click="submitTransfer">转移并重封存</el-button>
      </template>
    </el-dialog>

    <!-- ===== 录入认领申报 ===== -->
    <el-dialog v-model="claimDlg" title="录入认领申报（会员 / 未建档访客）" width="680px">
      <el-form label-width="110px">
        <el-form-item label="认领人类型" required>
          <el-radio-group v-model="claimForm.claimantType" @change="claimForm.memberId = null">
            <el-radio value="MEMBER">现有会员</el-radio><el-radio value="VISITOR">未建档访客</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="claimForm.claimantType === 'MEMBER'" label="选择会员" required>
          <el-select v-model="claimForm.memberId" filterable placeholder="选择会员档案" style="width: 100%">
            <el-option v-for="m in members" :key="m.id" :label="`${m.name}（${m.cardNo}）`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="访客姓名" required><el-input v-model="claimForm.claimantName" style="width: 240px" /></el-form-item>
        <el-form-item :label="claimForm.claimantType === 'VISITOR' ? '联系电话（必填）' : '联系电话（选填）'">
          <el-input v-model="claimForm.claimantPhone" style="width: 240px" placeholder="会员默认使用会员手机号" />
        </el-form-item>
        <el-form-item label="物品描述" required>
          <el-input v-model="claimForm.claimDesc" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="遗失时间范围" required>
          <el-date-picker v-model="claimRange" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss"
            range-separator="至" start-placeholder="遗失起" end-placeholder="遗失止" style="width: 380px" />
        </el-form-item>
        <el-form-item label="可能出现位置">
          <el-select v-model="claimPosType" style="width: 120px">
            <el-option label="箭道" value="LANE" /><el-option label="课程" value="COURSE" /><el-option label="其他区域" value="AREA" />
          </el-select>
          <el-select v-if="claimPosType === 'LANE'" v-model="claimForm.possibleLaneId" style="width: 200px; margin-left: 8px">
            <el-option v-for="l in options.lanes || []" :key="l.id" :label="l.laneNo" :value="l.id" />
          </el-select>
          <el-select v-if="claimPosType === 'COURSE'" v-model="claimForm.possibleCourseId" style="width: 260px; margin-left: 8px">
            <el-option v-for="c in options.courses || []" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <el-input v-if="claimPosType === 'AREA'" v-model="claimForm.possibleArea" style="width: 260px; margin-left: 8px" placeholder="如前台休息区" />
        </el-form-item>
        <el-form-item label="隐私核验特征" required>
          <el-input v-model="claimForm.privacyFeatures" type="textarea" :rows="2" maxlength="500" show-word-limit
            placeholder="只有失主才知道的细节（刻字 / 暗记 / 序列号尾号等）；仅值班经理审核可见，不对外公示" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="claimDlg = false">取消</el-button>
        <el-button type="primary" @click="submitClaim">提交值班经理审核</el-button>
      </template>
    </el-dialog>

    <!-- ===== 补证 ===== -->
    <el-dialog v-model="supplementDlg" title="认领人补充证明材料" width="520px">
      <el-input v-model="supplementText" type="textarea" :rows="4" maxlength="500" show-word-limit
        placeholder="购买凭证 / 照片 / 其他可证明归属的材料说明（追加留痕，不覆盖首次申报）" />
      <template #footer>
        <el-button @click="supplementDlg = false">取消</el-button>
        <el-button type="warning" @click="submitSupplement">录入并重新提交审核</el-button>
      </template>
    </el-dialog>

    <!-- ===== 经理审核（三结论） ===== -->
    <el-dialog v-model="reviewDlg" title="值班经理认领审核（最小信息）" width="760px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px"
        title="此处只展示完成核验所需的最少信息；现场照片默认不调阅。审核须分别给出特征匹配、时间地点吻合、补充证明三项结论。" />
      <div v-if="detail" class="review-box">
        <div><b>登记外观：</b>{{ detail.appearance }}</div>
        <div v-if="detail.activeClaim"><b>认领描述：</b>{{ detail.activeClaim.claimDesc }}</div>
        <div v-if="detail.activeClaim"><b>遗失时间：</b>{{ fullTime(detail.activeClaim.lostTimeFrom) }} ~ {{ fullTime(detail.activeClaim.lostTimeTo) }}
          ；发现时间 {{ fullTime(detail.foundAt) }}</div>
        <div v-if="detail.activeClaim"><b>可能位置：</b>
          {{ detail.activeClaim.possibleLaneNo || detail.activeClaim.possibleCourseName || detail.activeClaim.possibleArea || '—' }}
          ；发现位置 {{ detail.foundLocationTypeName }} {{ detail.foundArea }}
        </div>
        <div v-if="detail.activeClaim" class="danger-text"><b>隐私特征：</b>{{ detail.activeClaim.privacyFeatures }}</div>
        <div v-if="detail.activeClaim && detail.activeClaim.supplementary"><b>补证材料：</b>{{ detail.activeClaim.supplementary }}</div>
        <el-button size="small" plain type="danger" style="margin-top: 6px" :loading="photoLoading" @click="loadReviewPack(true)">
          确需调阅现场照片（仅本次审核可见并留痕）
        </el-button>
        <div v-if="detail.photoRefs && detail.photoRefs.length" style="margin-top: 6px">
          <el-tag v-for="(p, i) in detail.photoRefs" :key="i" size="small" type="danger" effect="dark" class="scope-tag">{{ p }}</el-tag>
        </div>
      </div>
      <el-divider />
      <el-form label-width="110px">
        <el-form-item label="特征匹配" required>
          <el-radio-group v-model="reviewForm.featureResult">
            <el-radio value="MATCH">匹配</el-radio><el-radio value="MISMATCH">不符</el-radio><el-radio value="UNKNOWN">待核</el-radio>
          </el-radio-group>
          <el-input v-model="reviewForm.featureNote" placeholder="特征核验说明（选填）" style="margin-top: 6px" />
        </el-form-item>
        <el-form-item label="时间地点吻合" required>
          <el-radio-group v-model="reviewForm.timeplaceResult">
            <el-radio value="MATCH">吻合</el-radio><el-radio value="MISMATCH">不符</el-radio><el-radio value="UNKNOWN">待核</el-radio>
          </el-radio-group>
          <el-input v-model="reviewForm.timeplaceNote" placeholder="时间地点核验说明（选填）" style="margin-top: 6px" />
        </el-form-item>
        <el-form-item label="补充证明" required>
          <el-radio-group v-model="reviewForm.supportResult">
            <el-radio value="MATCH">通过</el-radio><el-radio value="MISMATCH">不符</el-radio><el-radio value="UNKNOWN">待核</el-radio>
          </el-radio-group>
          <el-input v-model="reviewForm.supportNote" placeholder="补充证明核验说明（选填）" style="margin-top: 6px" />
        </el-form-item>
        <el-form-item label="终判" required>
          <el-radio-group v-model="reviewForm.decision">
            <el-radio value="APPROVE">通过（进入待交还）</el-radio>
            <el-radio value="NEED_MORE">要求补证</el-radio>
            <el-radio value="REJECT">驳回</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="审核意见" required>
          <el-input v-model="reviewForm.reviewNote" type="textarea" :rows="3" maxlength="500" show-word-limit
            :placeholder="reviewForm.decision === 'APPROVE' ? '通过依据' : '要求补证 / 驳回必须填写具体原因'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewDlg = false">取消</el-button>
        <el-button type="primary" @click="submitReview">提交审核结论</el-button>
      </template>
    </el-dialog>

    <!-- ===== 双人现场交还 ===== -->
    <el-dialog v-model="handoverDlg" title="双人现场认领交还" width="600px">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 10px"
        title="必须由当前保管人与另一名工作人员共同完成；领取人临时变更、封签破损等请先登记异常交接。" />
      <el-form label-width="120px">
        <el-form-item label="当前保管人" required><el-input v-model="handoverForm.custodian" /></el-form-item>
        <el-form-item label="保管人岗位" required>
          <el-radio-group v-model="handoverForm.custodianRole">
            <el-radio value="FRONT">前台</el-radio><el-radio value="KEEPER">保管员</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="另一名工作人员" required><el-input v-model="handoverForm.witness" placeholder="必须不同于保管人" /></el-form-item>
        <el-form-item label="见证人岗位" required>
          <el-select v-model="handoverForm.witnessRole" style="width: 200px">
            <el-option label="前台" value="FRONT" /><el-option label="保管员" value="KEEPER" />
            <el-option label="教练" value="COACH" /><el-option label="值班经理" value="MANAGER" />
          </el-select>
        </el-form-item>
        <el-divider content-position="left">实际领取人证件核验</el-divider>
        <el-form-item label="领取人姓名" required><el-input v-model="handoverForm.receiver.name" /></el-form-item>
        <el-form-item label="证件核验方式" required>
          <el-select v-model="handoverForm.receiver.idType" style="width: 240px">
            <el-option v-for="t in options.idTypes || []" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="证件标识" required><el-input v-model="handoverForm.receiver.idNo" placeholder="身份证后四位等" style="width: 240px" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handoverDlg = false">取消</el-button>
        <el-button type="success" @click="submitHandover">双方确认，完成交还</el-button>
      </template>
    </el-dialog>

    <!-- ===== 异常上报 ===== -->
    <el-dialog v-model="exceptionDlg" title="登记异常交接（暂停交还 · 保留原审核）" width="560px">
      <el-form label-width="110px">
        <el-form-item label="异常原因" required>
          <el-radio-group v-model="exceptionForm.reason">
            <el-radio value="SEAL_DAMAGED">封签破损</el-radio>
            <el-radio value="ITEM_MISMATCH">实物与登记不符</el-radio>
            <el-radio value="RECIPIENT_CHANGED">领取人临时变更</el-radio>
            <el-radio value="CUSTODIAN_ABSENT">保管人无法到场</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="情况说明" required>
          <el-input v-model="exceptionForm.detail" type="textarea" :rows="4" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="exceptionDlg = false">取消</el-button>
        <el-button type="danger" @click="submitException">转入异常挂起</el-button>
      </template>
    </el-dialog>

    <!-- ===== 经理异常处置 ===== -->
    <el-dialog v-model="resolveDlg" title="值班经理异常处置" width="580px">
      <el-alert type="error" :closable="false" show-icon style="margin-bottom: 10px"
        title="任何处置都不能跳过双人交还：重新封存 / 补充核验后仍须双人确认；终止则本次认领关闭，不能重新改成已交还。" />
      <el-form label-width="110px">
        <el-form-item label="处置方式" required>
          <el-radio-group v-model="resolveForm.resolution">
            <el-radio value="RESEAL">重新封存</el-radio>
            <el-radio value="RECHECK">补充核验</el-radio>
            <el-radio value="TERMINATE">终止本次认领</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="resolveForm.resolution === 'RESEAL'">
          <el-form-item label="新封签保管人" required><el-input v-model="resolveForm.custodian" /></el-form-item>
          <el-form-item label="保管人岗位" required>
            <el-radio-group v-model="resolveForm.custodianRole">
              <el-radio value="FRONT">前台</el-radio><el-radio value="KEEPER">保管员</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="存放区域" required>
            <el-select v-model="resolveForm.storageArea" style="width: 100%">
              <el-option v-for="a in options.storageAreas || []" :key="a.code" :label="a.name" :value="a.code" />
            </el-select>
          </el-form-item>
        </template>
        <el-form-item label="处置说明" required>
          <el-input v-model="resolveForm.detail" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveDlg = false">取消</el-button>
        <el-button type="danger" @click="submitResolve">提交处置</el-button>
      </template>
    </el-dialog>

    <!-- ===== 逾期处置 ===== -->
    <el-dialog v-model="disposeDlg" title="逾期失物处置登记" width="560px">
      <el-alert v-if="detail && (detail.highValueFlag || detail.identityFlag)" type="error" :closable="false" show-icon
        :title="(detail.highValueFlag ? '高价值物品' : '带身份信息物品') + '必须采用返还公安路径'" style="margin-bottom: 10px" />
      <el-form label-width="100px">
        <el-form-item label="处置结果" required>
          <el-radio-group v-model="disposeForm.result">
            <el-radio value="POLICE" :disabled="detail && (detail.highValueFlag || detail.identityFlag)">返还公安</el-radio>
            <el-radio v-if="!(detail && (detail.highValueFlag || detail.identityFlag))" value="DONATE">捐赠</el-radio>
            <el-radio v-if="!(detail && (detail.highValueFlag || detail.identityFlag))" value="SCRAP">报废</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="接收方" required><el-input v-model="disposeForm.receiverName" placeholder="派出所 / 受捐机构 / 报废单位" /></el-form-item>
        <el-form-item label="接收方电话"><el-input v-model="disposeForm.receiverContact" /></el-form-item>
        <el-form-item label="凭证编号" required><el-input v-model="disposeForm.receiptNo" placeholder="回执单号（必填，必须保留凭证）" /></el-form-item>
        <el-form-item label="凭证说明">
          <el-input v-model="disposeForm.receiptNote" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="disposeDlg = false">取消</el-button>
        <el-button type="danger" @click="submitDispose">登记处置结果</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { lostApi, memberApi, shortDate } from '../api'

const roles = [
  { code: 'FRONT', name: '前台' },
  { code: 'KEEPER', name: '保管员' },
  { code: 'COACH', name: '教练' },
  { code: 'MANAGER', name: '值班经理' }
]
const role = ref('FRONT')
const operator = ref('前台-马倩')
const roleName = computed(() => roles.find((r) => r.code === role.value)?.name || '')

const workTabs = [
  { key: 'custody', title: '保管链', scope: 'custody', hint: '封存 · 查看 · 转移 · 录入认领' },
  { key: 'review', title: '认领审核', scope: 'custody', hint: '值班经理三结论审核' },
  { key: 'handover', title: '现场交还', scope: 'return', hint: '双人确认 · 异常处置' },
  { key: 'disposal', title: '逾期处置', scope: 'overdue', hint: '返还公安 / 捐赠 / 报废 · 凭证' }
]
const tab = ref('public')
const workScope = ref('custody')
const options = ref({})
const members = ref([])

const publicItems = ref([])
const workRows = ref([])
const detail = ref(null)
const selectedId = ref(null)

const registeredRows = computed(() => workRows.value.filter((r) => r.status === 'REGISTERED'))

function can(action) {
  // 动作完全以后端按角色 + 状态下发的 allowedActions 为准，页面只渲染当前角色可执行的动作
  return (detail.value?.allowedActions || []).includes(action)
}
function roleOf(r) {
  return { FRONT: '前台', KEEPER: '保管员', COACH: '教练', MANAGER: '值班经理' }[r] || r
}

async function loadOptions() {
  options.value = await lostApi.options()
  memberApi.list({ includeMerged: false }).then((res) => { members.value = res || [] }).catch(() => {})
}
async function loadPublic() {
  publicItems.value = await lostApi.publicList()
}
async function loadWork(scope) {
  const useScope = typeof scope === 'string' ? scope : workScope.value
  workScope.value = useScope
  workRows.value = await lostApi.list({ role: role.value, scope: useScope })
  if (selectedId.value) await refreshDetail()
}
async function refreshDetail() {
  if (selectedId.value) detail.value = await lostApi.detail(selectedId.value, role.value)
}
async function onPick(row) {
  if (!row) return
  selectedId.value = row.id
  detail.value = await lostApi.detail(row.id, role.value)
}

watch(role, async () => {
  if (tab.value !== 'public' && tab.value !== 'register') await loadWork()
  await refreshDetail()
})
watch(tab, async (v) => {
  const t = workTabs.find((x) => x.key === v)
  if (t) { workScope.value = t.scope; await loadWork(t.scope) }
  if (v === 'public') await loadPublic()
})

// ---------------- 登记 ----------------
const regForm = reactive({
  category: '', foundLocationType: 'LANE', foundLaneId: null, foundCourseId: null, foundArea: '',
  foundAt: '', appearance: '', photoRefs: '', highValueFlag: false, identityFlag: false,
  finderName: '', finderContact: '', finderRole: 'MEMBER'
})
async function submitRegister() {
  try {
    const v = await lostApi.register({ ...regForm, operator: operator.value })
    ElMessage.success('失物已登记，请生成封签完成首次交接')
    selectedId.value = v.id
    detail.value = v
    tab.value = 'custody'
    await loadWork('custody')
  } catch (e) { /* 拦截器已提示 */ }
}

// ---------------- 首次封存 ----------------
const firstSealDlg = ref(false)
const sealForm = reactive({ custodian: '', custodianRole: 'FRONT', storageArea: 'FRONT_DESK' })
function openFirstSeal() {
  Object.assign(sealForm, { custodian: operator.value, custodianRole: role.value === 'KEEPER' ? 'KEEPER' : 'FRONT', storageArea: detail.value.highValueFlag ? 'SAFE' : 'FRONT_DESK' })
  firstSealDlg.value = true
}
async function submitFirstSeal() {
  try {
    detail.value = await lostApi.firstSeal(selectedId.value, { ...sealForm, operator: operator.value })
    firstSealDlg.value = false
    await loadWork()
  } catch (e) {}
}

// ---------------- 查看 / 重封存 / 转移 ----------------
const inspectDlg = ref(false)
const inspectForm = reactive({ reason: '' })
function openInspect() {
  inspectForm.reason = ''
  inspectDlg.value = true
}
async function submitInspect() {
  if (!inspectForm.reason.trim()) return ElMessage.warning('请填写开封查看原因')
  try {
    detail.value = await lostApi.inspect(selectedId.value, {
      operator: operator.value, operatorRole: role.value, reasonNote: inspectForm.reason
    })
    inspectDlg.value = false
    ElMessage.warning('已开封，请核验后立即重新封存')
  } catch (e) {}
}

const resealDlg = ref(false)
const resealForm = reactive({ note: '' })
function openReseal() {
  Object.assign(sealForm, { custodian: detail.value.currentCustodian || operator.value, custodianRole: detail.value.currentCustodianRole || 'FRONT', storageArea: detail.value.currentStorageArea || 'FRONT_DESK' })
  resealForm.note = ''
  resealDlg.value = true
}
async function submitReseal() {
  try {
    detail.value = await lostApi.reseal(selectedId.value, { ...sealForm, operator: operator.value, note: resealForm.note })
    resealDlg.value = false
    await loadWork()
  } catch (e) {}
}

const transferDlg = ref(false)
const transferForm = reactive({ toCustodian: '', toCustodianRole: 'KEEPER', toStorageArea: 'STORAGE', note: '' })
function openTransfer() {
  Object.assign(transferForm, { toCustodian: '', toCustodianRole: 'KEEPER', toStorageArea: 'STORAGE', note: '' })
  transferDlg.value = true
}
async function submitTransfer() {
  try {
    detail.value = await lostApi.transfer(selectedId.value, {
      ...transferForm, operator: operator.value, operatorRole: role.value
    })
    transferDlg.value = false
    await loadWork()
  } catch (e) {}
}

// ---------------- 认领 ----------------
const claimDlg = ref(false)
const claimForm = reactive({
  claimantType: 'MEMBER', memberId: null, claimantName: '', claimantPhone: '',
  claimDesc: '', possibleLaneId: null, possibleCourseId: null, possibleArea: '', privacyFeatures: ''
})
const claimRange = ref([])
const claimPosType = ref('LANE')
function blankClaim() {
  Object.assign(claimForm, {
    claimantType: 'MEMBER', memberId: null, claimantName: '', claimantPhone: '',
    claimDesc: '', possibleLaneId: null, possibleCourseId: null, possibleArea: '', privacyFeatures: ''
  })
  claimRange.value = []
  claimPosType.value = 'LANE'
}
function openClaim() {
  blankClaim()
  claimDlg.value = true
}
async function submitClaim() {
  try {
    const payload = {
      ...claimForm,
      lostTimeFrom: claimRange.value?.[0] || null,
      lostTimeTo: claimRange.value?.[1] || null,
      operator: operator.value
    }
    detail.value = await lostApi.createClaim(selectedId.value, payload)
    claimDlg.value = false
    await loadWork()
  } catch (e) {}
}

const supplementDlg = ref(false)
const supplementText = ref('')
function openSupplement() { supplementText.value = ''; supplementDlg.value = true }
async function submitSupplement() {
  if (!supplementText.value.trim()) return ElMessage.warning('请填写补充证明材料')
  try {
    detail.value = await lostApi.supplement(detail.value.activeClaim.id, {
      supplementary: supplementText.value, operator: operator.value
    })
    supplementDlg.value = false
  } catch (e) {}
}

// ---------------- 审核 ----------------
const reviewDlg = ref(false)
const photoLoading = ref(false)
const reviewForm = reactive({
  featureResult: 'UNKNOWN', timeplaceResult: 'UNKNOWN', supportResult: 'UNKNOWN',
  featureNote: '', timeplaceNote: '', supportNote: '', decision: 'NEED_MORE', reviewNote: ''
})
function openReview() {
  Object.assign(reviewForm, {
    featureResult: 'UNKNOWN', timeplaceResult: 'UNKNOWN', supportResult: 'UNKNOWN',
    featureNote: '', timeplaceNote: '', supportNote: '', decision: 'NEED_MORE', reviewNote: ''
  })
  reviewDlg.value = true
}
async function loadReviewPack(withPhoto) {
  photoLoading.value = true
  try {
    detail.value = await lostApi.reviewPack(selectedId.value, withPhoto)
  } finally {
    photoLoading.value = false
  }
}
async function submitReview() {
  if (!reviewForm.reviewNote.trim()) return ElMessage.warning('请填写审核意见')
  try {
    detail.value = await lostApi.review(detail.value.activeClaim.id, { ...reviewForm, manager: operator.value })
    reviewDlg.value = false
    ElMessage.success('审核结论已记录')
    await loadWork()
  } catch (e) {}
}

// ---------------- 现场交还 ----------------
async function doStartHandover() {
  try {
    detail.value = await lostApi.startHandover(selectedId.value, operator.value)
    ElMessage.success('已建立现场交接，请进行双人确认')
  } catch (e) {}
}
const handoverDlg = ref(false)
const handoverForm = reactive({
  custodian: '', custodianRole: 'FRONT', witness: '', witnessRole: 'FRONT',
  receiver: { name: '', idType: 'ID_CARD', idNo: '' }
})
function openHandover() {
  const h = detail.value.handover
  Object.assign(handoverForm, {
    custodian: h?.custodian || detail.value.currentCustodian || '',
    custodianRole: h?.custodianRole || detail.value.currentCustodianRole || 'FRONT',
    witness: '', witnessRole: 'FRONT',
    receiver: { name: detail.value.activeClaim?.claimantName || '', idType: 'ID_CARD', idNo: '' }
  })
  handoverDlg.value = true
}
async function submitHandover() {
  try {
    detail.value = await lostApi.completeHandover(selectedId.value, {
      ...handoverForm, operator: operator.value
    })
    handoverDlg.value = false
    ElMessage.success('双人现场交还完成')
    await loadWork()
  } catch (e) {}
}

// ---------------- 异常 ----------------
const exceptionDlg = ref(false)
const exceptionForm = reactive({ reason: 'SEAL_DAMAGED', detail: '' })
function openException() {
  Object.assign(exceptionForm, { reason: 'SEAL_DAMAGED', detail: '' })
  exceptionDlg.value = true
}
async function submitException() {
  if (!exceptionForm.detail.trim()) return ElMessage.warning('请填写异常情况说明')
  try {
    detail.value = await lostApi.reportException(selectedId.value, {
      ...exceptionForm, operator: operator.value, operatorRole: role.value
    })
    exceptionDlg.value = false
    ElMessage.error('已转入异常交接，等待值班经理处置')
    await loadWork('return')
  } catch (e) {}
}

const resolveDlg = ref(false)
const resolveForm = reactive({
  resolution: 'RESEAL', detail: '', custodian: '', custodianRole: 'KEEPER', storageArea: 'FRONT_DESK'
})
function openResolve() {
  Object.assign(resolveForm, {
    resolution: 'RESEAL', detail: '',
    custodian: detail.value.currentCustodian || '',
    custodianRole: detail.value.currentCustodianRole || 'KEEPER',
    storageArea: detail.value.currentStorageArea || 'FRONT_DESK'
  })
  resolveDlg.value = true
}
async function submitResolve() {
  if (!resolveForm.detail.trim()) return ElMessage.warning('请填写处置说明')
  try {
    detail.value = await lostApi.resolveException(selectedId.value, { ...resolveForm, manager: operator.value })
    resolveDlg.value = false
    ElMessage.success('异常处置已记录')
    await loadWork('return')
  } catch (e) {}
}

// ---------------- 逾期处置 ----------------
async function doMarkOverdue() {
  try {
    const count = await lostApi.markOverdue({ manager: operator.value })
    ElMessage.success(`已将 ${count} 件逾期物品转入待处置清单`)
    await loadWork('overdue')
  } catch (e) {}
}
const disposeDlg = ref(false)
const disposeForm = reactive({ result: 'POLICE', receiverName: '', receiverContact: '', receiptNo: '', receiptNote: '' })
function openDispose() {
  Object.assign(disposeForm, {
    result: detail.value.highValueFlag || detail.value.identityFlag ? 'POLICE' : 'DONATE',
    receiverName: '', receiverContact: '', receiptNo: '', receiptNote: ''
  })
  disposeDlg.value = true
}
async function submitDispose() {
  try {
    detail.value = await lostApi.dispose(selectedId.value, { ...disposeForm, manager: operator.value })
    disposeDlg.value = false
    ElMessage.success('处置结果与凭证已登记')
    await loadWork('overdue')
  } catch (e) {}
}

// ---------------- 展示辅助 ----------------
function fullTime(v) { return v ? String(v).replace('T', ' ').slice(0, 19) : '' }
function itemStatusType(s) {
  return { REGISTERED: 'info', IN_CUSTODY: 'success', TO_RETURN: 'warning', EXCEPTION: 'danger',
    RETURNED: '', TO_DISPOSE: 'warning', DISPOSED: 'info' }[s] || 'info'
}
function claimStatusType(s) {
  return { PENDING: 'warning', NEED_MORE: 'warning', APPROVED: 'success', REJECTED: 'danger',
    EXCEPTION: 'danger', TERMINATED: 'info', RETURNED: '' }[s] || 'info'
}
function resultType(r) { return r === 'MATCH' ? 'success' : r === 'MISMATCH' ? 'danger' : 'info' }
function decisionType(d) { return d === 'APPROVE' ? 'success' : d === 'REJECT' ? 'danger' : 'warning' }
function handoverType(s) { return s === 'DONE' ? 'success' : s === 'EXCEPTION' ? 'danger' : 'warning' }
function logDotType(a) {
  if (['HANDOVER_DONE', 'FIRST_SEAL', 'APPROVE', 'RESEAL'].includes(a)) return 'success'
  if (['EXCEPTION', 'REJECT', 'UNSEAL', 'DISPOSE'].includes(a)) return 'danger'
  if (['NEED_MORE', 'MARK_OVERDUE', 'EXCEPTION_RESOLVE', 'TRANSFER'].includes(a)) return 'warning'
  return undefined
}

onMounted(async () => {
  await loadOptions()
  await Promise.all([loadPublic(), loadWork('custody')])
})
</script>

<style scoped>
.page { padding: 16px 20px; }
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.page-title { font-size: 18px; margin: 0; }
.page-title em { font-style: normal; font-size: 12px; color: var(--ink-mute); margin-left: 10px; font-weight: normal; }
.rule-tip { background: #f6f4ef; border: 1px solid #e6e0d2; border-radius: 8px; padding: 10px 12px; font-size: 12.5px; color: #5c5340; line-height: 1.7; margin-bottom: 12px; }
.board { display: grid; grid-template-columns: minmax(440px, 1fr) minmax(520px, 1.25fr); gap: 12px; }
.panel { background: #fff; border: 1px solid var(--line); border-radius: 10px; padding: 12px; margin-bottom: 12px; }
.panel-title { display: flex; align-items: center; gap: 8px; font-weight: 700; margin-bottom: 10px; }
.panel-title small { font-weight: 400; color: var(--ink-mute); }
.detail-head { border-bottom: 1px dashed var(--line); padding-bottom: 8px; margin-bottom: 8px; }
.detail-title { font-size: 15px; font-weight: 700; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.action-bar { display: flex; flex-wrap: wrap; gap: 6px; margin: 8px 0 12px; }
.block-title { font-weight: 700; font-size: 13px; margin: 14px 0 8px; border-left: 3px solid #b08948; padding-left: 8px; }
.fact-grid { display: grid; grid-template-columns: 1fr; gap: 6px; font-size: 12.5px; color: #4a5a56; }
.scope-tag { margin: 2px 4px 2px 0; }
.log-line { margin-top: 4px; padding-left: 4px; }
.log-detail { font-size: 12.5px; color: #4a5a56; }
.muted { color: var(--ink-mute); font-size: 12px; }
.mono { font-family: ui-monospace, Menlo, monospace; font-size: 12px; }
.review-box { background: #faf7f1; border: 1px solid #eee4d0; border-radius: 8px; padding: 10px; font-size: 12.5px; display: flex; flex-direction: column; gap: 6px; }
.danger-text { color: var(--el-color-danger); }
</style>
