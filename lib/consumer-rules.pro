# FastInflater consumer ProGuard rules
# 这些规则会自动合并到使用方的 ProGuard 配置中

# 保留公开 API
-keep class com.github.donglua.fastinflater.FastInflater { *; }
-keep class com.github.donglua.fastinflater.FastDataBinding { *; }
-keep class com.github.donglua.fastinflater.FastRecycledViewPool { *; }
-keep class com.github.donglua.fastinflater.ViewPool { *; }
-keep class com.github.donglua.fastinflater.ViewPool$WarmUpEntry { *; }
-keep class com.github.donglua.fastinflater.InflateTracker { *; }
-keep class com.github.donglua.fastinflater.InflateTracker$LayoutStat { *; }
-keep class com.github.donglua.fastinflater.PoolStats { *; }
-keep class com.github.donglua.fastinflater.PoolStats$Stat { *; }
-keep class com.github.donglua.fastinflater.ViewRecyclePolicy { *; }
-keep class com.github.donglua.fastinflater.GeneratedLayoutRegistry { *; }
-keep class com.github.donglua.fastinflater.GeneratedLayoutRegistry$Creator { *; }

# 保留 codegen 生成的类（Phase 2）
-keep class **.Gen_* { *; }
-keep class **.FastInflaterGenerated { *; }
