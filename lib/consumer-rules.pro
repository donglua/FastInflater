# FastInflater consumer ProGuard rules
# 这些规则会自动合并到使用方的 ProGuard 配置中

# 保留公开 API
-keep class com.aspect.fastinflater.FastInflater { *; }
-keep class com.aspect.fastinflater.FastDataBinding { *; }
-keep class com.aspect.fastinflater.FastRecycledViewPool { *; }
-keep class com.aspect.fastinflater.ViewPool { *; }
-keep class com.aspect.fastinflater.ViewPool$WarmUpEntry { *; }
-keep class com.aspect.fastinflater.InflateTracker { *; }
-keep class com.aspect.fastinflater.InflateTracker$LayoutStat { *; }
-keep class com.aspect.fastinflater.PoolStats { *; }
-keep class com.aspect.fastinflater.PoolStats$Stat { *; }
-keep class com.aspect.fastinflater.ViewRecyclePolicy { *; }
-keep class com.aspect.fastinflater.GeneratedLayoutRegistry { *; }
-keep class com.aspect.fastinflater.GeneratedLayoutRegistry$Creator { *; }

# 保留 codegen 生成的类（Phase 2）
-keep class **.Gen_* { *; }
-keep class **.FastInflaterGenerated { *; }
