import test from "node:test";
import assert from "node:assert/strict";
import {describeConversionFailure} from "../../main/resources/static/js/document-conversion-failure.mjs";

test("describes the failed stage, a useful action and the stored Korean wall time", () => {
    const value = {failureStage: "ORIGINAL_DOWNLOAD", failureCode: "ACCESS_DENIED", failedAt: "2026-11-22T12:34:56.123456"};
    const result = describeConversionFailure(value);
    assert.equal(result.stageLabel, "원본 파일 읽기");
    assert.match(result.message, /접근 권한.*운영자/);
    assert.equal(result.failedAtLabel, "실패 시각 2026-11-22 12:34:56");
    assert.equal(value.failedAt, "2026-11-22T12:34:56.123456");
});

test("missing and future codes have a fixed fallback without echoing untrusted values", () => {
    for (const code of [null, undefined, "UNKNOWN", "FUTURE_CODE", "__proto__", "constructor", "<img src=x onerror=alert(1)>"]) {
        const result = describeConversionFailure({failureCode: code, failureStage: code, failedAt: "<script>bad</script>"});
        assert.equal(result.stageLabel, "실패 단계 정보 없음");
        assert.equal(result.failedAtLabel, "실패 시각 정보 없음");
        assert.match(result.message, /원인을 확인/);
        assert.doesNotMatch(JSON.stringify(result), /<img|<script|FUTURE_CODE|__proto__/);
    }
});

test("persistent file and size failures explain replacing the original while transient failures allow waiting", () => {
    for (const failureCode of ["FILE_NOT_FOUND", "SIZE_LIMIT_EXCEEDED"]) {
        assert.match(describeConversionFailure({failureCode}).message, /새 버전/);
    }
    for (const failureCode of ["CONNECTION_FAILED", "TIMEOUT", "RESULT_SAVE_FAILED"]) {
        assert.match(describeConversionFailure({failureCode}).message, /잠시 후/);
    }
});
