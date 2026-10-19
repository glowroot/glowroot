# Preload constructor OnBefore advice (JRebel / #1084)

## What

While `PointcutClassFileTransformer` is registered, Glowroot now force-loads
plugin advice classes that target `<init>` with `@OnBefore` (notably executor
`ThreadInit*` advice) so the enabled-boolean bridge method is present before
`initialReweave`.

## Why

With a native agent such as JRebel, `initialReweave` can hit weave errors that
create threads while still inside a `ClassFileTransformer`. Loading
`ExecutorAspect$ThreadInitWithThreadGroupAdvice` in that reentrant path skips
the pointcut transform, so woven `Thread.<init>` calls
`onBefore(Z...)` and gets `NoSuchMethodError` ([#1084](https://github.com/glowroot/glowroot/issues/1084)).

## How

- `AdviceCache.preloadConstructorOnBeforeAdviceClasses()` — `Class.forName(..., false, …)`
  for each constructor `@OnBefore` advice (define only; no early `<clinit>`)
- `AgentModule` calls that before removing `PointcutClassFileTransformer`
- Unit tests: `Z` bridge transform, preload selection predicate, AgentModule wiring guard

## Choices

Preload during the existing pointcut-transform window instead of auto-disabling
the executor plugin under JRebel, so async context capture still works when
startup succeeds. Does not remove the separate “important classes loaded before
Glowroot” warning when native agents load first.

## Verification

- `mvn test -Dtest=PointcutClassFileTransformerTest -pl :glowroot-agent-core`
- Manual: IntelliJ + JRebel + Glowroot with executor plugin enabled (issue
  reporter scenario) — startup should no longer NSME on Thread init advice
