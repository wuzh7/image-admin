import assert from 'node:assert/strict';
import test from 'node:test';
import { usedPromptNames } from '../src/pages/image/infringement/usedPrompts.ts';

test('shows only prompt1 when no prompt2 call was made', () => {
  assert.deepEqual(usedPromptNames(JSON.stringify([
    { callType: 'PROMPT1', promptName: 'gemini_prompt.txt' },
    { callType: 'PROMPT1', promptName: 'gemini_prompt.txt' }
  ])), { names: ['gemini_prompt.txt'], inferred: false });
});

test('shows both actually called prompt names without duplicates', () => {
  assert.deepEqual(usedPromptNames(JSON.stringify([
    { callType: 'PROMPT1', promptName: 'gemini_prompt.txt' },
    { callType: 'PROMPT2', promptName: 'prompt2.txt' },
    { callType: 'PROMPT1', promptName: 'gemini_prompt.txt' },
    { callType: 'PROMPT2', promptName: 'prompt2.txt' }
  ])), { names: ['gemini_prompt.txt', 'prompt2.txt'], inferred: false });
});

test('uses recorded filenames rather than current configuration or fixed names', () => {
  assert.deepEqual(usedPromptNames('[{"callType":"PROMPT1","promptName":"review.txt"},{"callType":"PROMPT2","promptName":"lookup-v2.txt","errorCode":"UPSTREAM_ERROR"}]'),
    { names: ['review.txt', 'lookup-v2.txt'], inferred: false });
});

test('marks old audit filename mappings as inferred, but does not invent unused prompt2', () => {
  assert.deepEqual(usedPromptNames('[{"callType":"PROMPT1"}]'),
    { names: ['gemini_prompt.txt'], inferred: true });
  assert.deepEqual(usedPromptNames('[{"callType":"PROMPT1"},{"callType":"PROMPT2"}]'),
    { names: ['gemini_prompt.txt', 'prompt2.txt'], inferred: true });
});

test('missing or malformed audit is not evidence of any used prompt', () => {
  for (const raw of [undefined, 'bad JSON', '{}', '[null,1,{"callType":"OTHER"}]', '[]']) {
    assert.deepEqual(usedPromptNames(raw), { names: [], inferred: false });
  }
});
