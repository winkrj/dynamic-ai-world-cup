// Product copy, not a model-authored question or a server-side conversation turn.
export const clarificationQuestion = '무엇을 고르는 월드컵인가요? 비교할 대상이나 활동을 알려주세요.';
export const promptLength = (value: string): number => [...value].length;
export const clarifiedPrompt = (original: string, answer: string): string => `${original}\n\n추가 답변: ${answer.trim()}`;
export function clarificationProblem(original: string, answer: string): string | null {
  if (!answer.trim()) return '비교할 대상이나 활동을 적어 주세요.';
  const excess = promptLength(clarifiedPrompt(original, answer)) - 500;
  return excess > 0 ? `처음 고민과 답변을 합쳐 500자까지 보낼 수 있어요. 답변을 ${excess}자 줄이거나 처음 고민을 수정해 주세요.` : null;
}
