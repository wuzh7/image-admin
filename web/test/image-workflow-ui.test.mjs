import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const dedupPage = readFileSync(new URL('../src/pages/image/dedup/index.tsx', import.meta.url), 'utf8');
const dedupStyles = readFileSync(new URL('../src/assets/styles/pages/image-dedup.less', import.meta.url), 'utf8');
const dashboard = readFileSync(new URL('../src/pages/index.tsx', import.meta.url), 'utf8');

const similarGrid = dedupPage.slice(
  dedupPage.indexOf('function SimilarImageGrid'),
  dedupPage.indexOf('function statusTag')
);
const reviewPanel = dedupPage.slice(
  dedupPage.indexOf('const reviewPanel'),
  dedupPage.indexOf('const libraryPanel')
);

test('shows 50 library images with 50 and 100 as the size choices', () => {
  assert.match(dedupPage, /const \[libraryPageSize, setLibraryPageSize\] = useState\(50\)/);
  assert.match(dedupPage, /pageSizeOptions=\{\[50, 100\]\}/);
});

test('shows at most six review candidates that reach the batch threshold', () => {
  assert.match(similarGrid, /Number\(candidate\.similarity\) >= threshold/);
  assert.match(similarGrid, /\.slice\(0, reviewCandidatePageSize\)/);
  assert.doesNotMatch(similarGrid, /<Pagination/);
});

test('loads review images by scrolling instead of page controls', () => {
  assert.match(reviewPanel, /className="image-review-scroll"/);
  assert.match(reviewPanel, /onScroll=/);
  assert.doesNotMatch(reviewPanel, /<Pagination/);
  assert.match(dedupStyles, /\.image-review-scroll\s*\{[^}]*overflow-y:\s*auto/s);
});

test('marks yesterday and older review work as due today', () => {
  assert.ok((dashboard.match(/< today/g) || []).length >= 2);
  assert.ok((dashboard.match(/历史待办，今日必须完成/g) || []).length >= 2);
});
