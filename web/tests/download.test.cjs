const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

test('file exports are not cut off by the API request timeout', async () => {
  let requestConfig;
  const modules = {
    antd: { message: { loading: () => () => {}, error: () => {} } },
    '@/api/request': async config => { requestConfig = config; return {}; },
    '@/utils/wuzh': { tansParams: () => '', blobValidate: async () => false, parseBlobErrorMessage: async () => '' }
  };
  const source = readFileSync(join(__dirname, '../src/utils/download.ts'), 'utf8');
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText;
  const exports = {};
  vm.runInNewContext(code, { exports, require: id => modules[id] });

  await exports.download('/images/export', {}, 'library.zip');

  assert.equal(requestConfig.timeout, 0);
});
