package com.archery.range.common;

/**
 * 监护授权 / 现场交接的放行拦截异常：拦截决定（含 BLOCK / FREEZE 轨迹）必须随事务提交留痕，
 * 同时把被拦截的环节与原因透传给前台。因此服务方法以 noRollbackFor 声明本异常不触发回滚
 * （与 {@link BaselineStaleException} 同一处理模式）：
 * 资源台账不产生任何部分放行，但「哪一步、为什么被拦」的事实不会丢失，重试也不会制造重复轨迹。
 */
public class GuardianBlockedException extends BizException {

    public GuardianBlockedException(String message) {
        super(message);
    }
}
