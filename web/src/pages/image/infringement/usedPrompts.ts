export function usedPromptNames(raw?: string): { names: string[]; inferred: boolean } {
  const names = new Set<string>();
  let inferred = false;
  try {
    const audit: unknown = JSON.parse(raw || 'null');
    if (!Array.isArray(audit)) return { names: [], inferred: false };
    for (const call of audit) {
      if (call?.callType !== 'PROMPT1' && call?.callType !== 'PROMPT2') continue;
      if (typeof call.promptName === 'string' && call.promptName.trim()) {
        names.add(call.promptName.trim());
      } else {
        names.add(call.callType === 'PROMPT1' ? 'gemini_prompt.txt' : 'prompt2.txt');
        inferred = true;
      }
    }
  } catch { return { names: [], inferred: false }; }
  return { names: [...names], inferred };
}
