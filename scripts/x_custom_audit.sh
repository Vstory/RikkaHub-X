#!/usr/bin/env bash
# ============================================================
# X 定制审计：① 标签存活(防 merge 静默丢定制) ② 上游合并预演(冲突 + 双方都改清单)
# SCRIPT_VERSION=1.0.0
#
# 用法：
#   bash scripts/x_custom_audit.sh                 # 全部检查(默认对比 upstream/master)
#   bash scripts/x_custom_audit.sh <上游ref>        # 指定上游 ref(如 upstream/master)
#   AUDIT_SKIP_UPSTREAM=1 bash scripts/x_custom_audit.sh   # 只做标签存活检查
#
# 背景(为什么需要这个脚本)：
#   本仓库是 rikkahub/rikkahub 的 fork，main 上叠加了大量 X 定制。合并上游时有两类风险
#   静态检查抓不全，一个语法检查也覆盖不到：
#     · 文本冲突    —— git 层面能发现，但往往等到合并那一刻才暴露
#     · 语义冲突    —— 文本无冲突，但 X 的补丁在合并后**逻辑失效**(上游重构了调用点)
#     · 静默丢弃    —— merge 时误取上游版本，把 X 定制连带标签一起丢掉
#   本脚本把能自动化的部分做掉：冲突提前暴露 + 输出「双方都改过」的可疑清单供人工判断。
#   语义层面能否真的还成立，只有针对性用例(x-custom-guard)和真机验证能回答。
#
# 退出码：0 = 无可自动判定的问题；1 = 检测到文本冲突或标签消失。
# ============================================================

set -uo pipefail   # 刻意不用 -e：多个检查要各自跑完并汇总

TAG='X-custom'
SRC_DIRS=('app/src/main' 'ai/src/main' 'workspace/src/main')
UPSTREAM_REF="${1:-upstream/master}"

fail=0
SUMMARY="${GITHUB_STEP_SUMMARY:-}"

# ── 输出助手：同时打到 stdout 与 GitHub Job Summary ──────────────
say() {
  echo "$*"
  [ -n "$SUMMARY" ] && printf '%s\n' "$*" >> "$SUMMARY"
  return 0
}

# ── 收集带定制标签的源码文件 ────────────────────────────────────
tagged_files() {
  local d
  for d in "${SRC_DIRS[@]}"; do
    [ -d "$d" ] && grep -rl --include='*.kt' -e "$TAG" "$d" 2>/dev/null
  done | sort -u
}

say "## 🔍 X 定制审计"
say ""

# ══════════════════════════════════════════════════════════════
# ① 标签存活：本次提交相对上一个提交，有没有文件把标签丢了
#    对 merge commit，HEAD^ 是第一个父 = 合并前的 main，正好用于对比
# ══════════════════════════════════════════════════════════════
say "### ① 定制标签存活"
say ""

current_list="$(tagged_files)"
current_count="$(printf '%s\n' "$current_list" | grep -c . || true)"
say "- 当前带 \`[X-custom]\` 标签的源码文件：**${current_count}** 个"

if git rev-parse --verify -q HEAD^ >/dev/null 2>&1; then
  prev_list="$(git grep -l -e "$TAG" HEAD^ -- '*.kt' 2>/dev/null \
    | sed 's/^HEAD^://' \
    | grep -E "^($(IFS='|'; echo "${SRC_DIRS[*]}"))" | sort -u)"

  # 上一个提交有标签、当前没有 → 要么文件被删，要么标签被 merge 覆盖
  lost="$(comm -23 <(printf '%s\n' "$prev_list" | grep .) <(printf '%s\n' "$current_list" | grep .) || true)"

  if [ -n "$lost" ]; then
    say ""
    say "> ⚠️ **有文件在上一个提交带标签、当前不带** —— 可能是 merge 覆盖了 X 定制，"
    say "> 也可能是本次刻意删除了定制。请确认属于哪种："
    say ""
    printf '%s\n' "$lost" | sed 's/^/  - `/; s/$/`/' | while read -r l; do say "$l"; done
    # 文件彻底不存在 = 可能是刻意删除；文件还在但标签没了 = 强信号
    while read -r f; do
      [ -n "$f" ] || continue
      if [ -f "$f" ]; then
        say ""
        say "  ⚠️ \`$f\` **文件仍在但标签不见了** → 极可能是 merge 丢了定制"
        fail=1
      else
        say ""
        say "  ℹ️ \`$f\` 文件已不存在 → 若本次刻意删除定制，属预期"
      fi
    done <<< "$lost"
  else
    say "- ✅ 上一个提交带标签的文件，标签均仍在"
  fi
else
  say "- ℹ️ 无 HEAD^，跳过增量对比"
fi

# ══════════════════════════════════════════════════════════════
# ② 上游合并预演：文本冲突提前暴露 + 「双方都改过」清单
# ══════════════════════════════════════════════════════════════
say ""
say "### ② 上游合并预演（对比 \`${UPSTREAM_REF}\`）"
say ""

if [ "${AUDIT_SKIP_UPSTREAM:-0}" = "1" ]; then
  say "- ℹ️ 已按要求跳过（AUDIT_SKIP_UPSTREAM=1）"
else
  # CI 里没有 upstream remote，就地补上；已有则复用
  if ! git rev-parse --verify -q "$UPSTREAM_REF" >/dev/null 2>&1; then
    if ! git remote | grep -qx upstream; then
      git remote add upstream https://github.com/rikkahub/rikkahub.git 2>/dev/null || true
    fi
    if git fetch --quiet upstream master 2>/dev/null; then
      say "- 已 fetch 上游"
    else
      say "- ⚠️ 拉取上游失败（网络原因，非本仓库问题）—— 本次跳过预演"
      UPSTREAM_REF=""
    fi
  fi

  if [ -n "$UPSTREAM_REF" ] && git rev-parse --verify -q "$UPSTREAM_REF" >/dev/null 2>&1; then
    ahead="$(git rev-list --count "HEAD..${UPSTREAM_REF}" 2>/dev/null || echo '?')"
    behind="$(git rev-list --count "${UPSTREAM_REF}..HEAD" 2>/dev/null || echo '?')"
    say "- 上游领先本分支 **${ahead}** 个提交；本分支领先上游 **${behind}** 个提交（X 定制）"

    # ── 2a 文本冲突预演（只读，不碰工作区）────────────────────
    # git merge-tree --write-tree 退出码：0=无冲突，非 0=有冲突
    mt_out="$(git merge-tree --write-tree HEAD "$UPSTREAM_REF" 2>&1)"
    mt_code=$?

    say ""
    if [ "$mt_code" -ne 0 ]; then
      say "#### ❌ 检测到文本冲突"
      say ""
      say '```'
      printf '%s\n' "$mt_out" | tail -n +2 | head -50 | while read -r l; do say "$l"; done
      say '```'
      fail=1
    else
      say "- ✅ **文本冲突：无**（\`git merge-tree --write-tree\` 退出码 0）"
    fi

    # ── 2b 「双方都改过」= 语义冲突候选 ───────────────────────
    base="$(git merge-base HEAD "$UPSTREAM_REF" 2>/dev/null || true)"
    if [ -n "$base" ]; then
      both="$(comm -12 \
        <(git diff --name-only "$base" HEAD        | sort -u) \
        <(git diff --name-only "$base" "$UPSTREAM_REF" | sort -u) || true)"
      both_count="$(printf '%s\n' "$both" | grep -c . || true)"

      say ""
      say "- 自共同祖先 \`${base:0:8}\` 以来，**双方都改过**的文件：**${both_count}** 个"
      if [ "$both_count" -gt 0 ]; then
        say ""
        say "  文本可能不冲突，但这类文件是**语义冲突的唯一候选**，合并前请逐个过一遍："
        say ""
        while read -r f; do
          [ -n "$f" ] || continue
          # 标出是否带 X 定制标签：无标签却被双方都改过 = 最需要人眼确认
          if grep -q -e "$TAG" -- "$f" 2>/dev/null; then
            say "  - \`$f\`  ← X 定制文件 ✅ 已带标签"
          else
            say "  - \`$f\`  ← ⚠️ 无 X 标签（若有隐藏定制，请补标签并登记对账表）"
          fi
        done <<< "$both"
      fi
    else
      say "- ⚠️ 找不到共同祖先，跳过「双方都改过」统计"
    fi
  fi
fi

# ══════════════════════════════════════════════════════════════
say ""
if [ "$fail" -eq 0 ]; then
  say "### ✅ 结果：无可自动判定的问题"
  say ""
  say "> 说明：本脚本只能覆盖**文本冲突**与**标签丢失**。**语义冲突**（X 补丁在合并后逻辑失效）"
  say "> 无法静态判定，需靠 \`x-custom-guard\` 用例与真机验证。"
else
  say "### ❌ 结果：发现需要处理的问题（见上）"
fi

exit "$fail"
