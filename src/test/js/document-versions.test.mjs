import test from "node:test";
import assert from "node:assert/strict";
import {fetchDocumentVersions, fetchDocumentVersionPdfUrl, fetchDocumentVersionOriginalUrl} from "../../main/resources/static/js/document-versions.mjs";

const options = {workspaceId: 3, documentId: 10, documentVersionId: 91, expectedUserId: 7};
const version = (id = 91, number = 1) => ({id, documentId: 10, versionNumber: number, originalFileName: "서비스 기획서.docx",
    fileSize: 1024, pageCount: 2, uploadedBy: 7, uploadedByDisplayName: "작성자", createdAt: "2030-01-01T12:00:00", conversionStatus: "COMPLETED"});
function fixture({body = [version()], status = 200, userId = 7, invalidJson = false} = {}) {
    const calls = [];
    const fetchImpl = async (path, init) => {
        calls.push({path, init});
        if (path === "/api/v1/auth/me") return {ok: true, json: async () => ({id: userId})};
        return {ok: status === 200, status, json: async () => { if (invalidJson) throw new Error("bad JSON"); return body; }};
    };
    return {calls, fetchImpl};
}
const urlBody = () => ({documentVersionId: 91, versionNumber: 1, originalFileName: "기획서.docx",
    pdfUrl: "https://example.invalid/test.pdf?signature=test", downloadUrl: "https://example.invalid/test.docx?signature=test", expiresAt: "2099-01-01T00:00:00Z"});

test("lists only the requested document, sorts by version number and uses the current account without caching", async () => {
    const f = fixture({body: [version(91, 1), version(93, 3), version(92, 2)]});
    const signal = new AbortController().signal;
    const result = await fetchDocumentVersions({...options, fetchImpl: f.fetchImpl, signal});
    assert.deepEqual(result.map(item => item.id), [93, 92, 91]);
    assert.equal(f.calls[1].path, "/api/v1/workspaces/3/documents/10/versions");
    assert.ok(f.calls.every(call => call.init.credentials === "same-origin" && call.init.cache === "no-store" && call.init.signal === signal));
});

test("accepts an empty history and nullable display name, page count and conversion status", async () => {
    const empty = fixture({body: []});
    assert.deepEqual(await fetchDocumentVersions({...options, fetchImpl: empty.fetchImpl}), []);
    const f = fixture({body: [{...version(), uploadedByDisplayName: null, pageCount: null, conversionStatus: null}]});
    assert.equal((await fetchDocumentVersions({...options, fetchImpl: f.fetchImpl})).length, 1);
});

test("rejects mixed documents, duplicate IDs or version numbers and malformed history fields", async () => {
    for (const body of [{}, [null], [{...version(), documentId: 11}], [version(), version()], [version(), version(92, 1)],
        [{...version(), conversionStatus: "UNKNOWN"}], [{...version(), fileSize: -1}], [{...version(), createdAt: "invalid"}], [{...version(), uploadedBy: 0}]]) {
        const f = fixture({body});
        await assert.rejects(fetchDocumentVersions({...options, fetchImpl: f.fetchImpl}), /응답/);
    }
});

for (const status of [401, 403, 404, 409, 500]) test("history HTTP " + status + " is an error, not an empty list", async () => {
    const f = fixture({status});
    await assert.rejects(fetchDocumentVersions({...options, fetchImpl: f.fetchImpl}), error => error.status === status);
});

for (const [name, fetchUrl, suffix, field] of [["PDF", fetchDocumentVersionPdfUrl, "pdf-url", "pdfUrl"], ["original", fetchDocumentVersionOriginalUrl, "original-url", "downloadUrl"]]) {
    test(name + " URL uses the selected version and preserves matching version metadata", async () => {
        const f = fixture({body: urlBody()});
        const result = await fetchUrl({...options, fetchImpl: f.fetchImpl});
        assert.equal(f.calls[1].path, "/api/v1/workspaces/3/documents/10/versions/91/" + suffix);
        assert.equal(result.documentVersionId, 91);
        assert.equal(result.versionNumber, 1);
        assert.equal(result[field], urlBody()[field]);
    });
    test(name + " URL rejects another version, unsafe URLs and expired responses", async () => {
        for (const patch of [{documentVersionId: 92}, {versionNumber: 0}, {[field]: "javascript:alert(1)"},
            {[field]: "http://example.invalid/file"}, {[field]: "https://user:pass@example.invalid/file"},
            {[field]: "https://example.invalid/file#fragment"}, {expiresAt: "2000-01-01T00:00:00Z"}, {expiresAt: "bad"}]) {
            const f = fixture({body: {...urlBody(), ...patch}});
            await assert.rejects(fetchUrl({...options, fetchImpl: f.fetchImpl}), /버전|주소/);
        }
    });
    test(name + " URL refuses an account change before issuing another request", async () => {
        const f = fixture({body: urlBody(), userId: 8});
        await assert.rejects(fetchUrl({...options, fetchImpl: f.fetchImpl}), error => error.status === 401);
        assert.equal(f.calls.length, 1);
    });
    test(name + " URL rejects invalid IDs before any request", async () => {
        for (const key of ["workspaceId", "documentId", "documentVersionId", "expectedUserId"]) for (const value of [0, -1, undefined, "91", Number.MAX_SAFE_INTEGER + 1]) {
            const f = fixture({body: urlBody()});
            await assert.rejects(fetchUrl({...options, [key]: value, fetchImpl: f.fetchImpl}), error => error.status === 400);
            assert.equal(f.calls.length, 0);
        }
    });
}

test("original URL rejects a missing filename and invalid JSON is reported safely", async () => {
    const original = fixture({body: {...urlBody(), originalFileName: ""}});
    await assert.rejects(fetchDocumentVersionOriginalUrl({...options, fetchImpl: original.fetchImpl}));
    const f = fixture({invalidJson: true});
    await assert.rejects(fetchDocumentVersions({...options, fetchImpl: f.fetchImpl}), /응답/);
});

test("preserves the matching failed version's stage, code and ISO time without changing the response object", async () => {
    const item = {...version(), conversionStatus: "FAILED", failureStage: "ORIGINAL_DOWNLOAD",
        failureCode: "ACCESS_DENIED", failedAt: "2026-11-22T12:34:56.123456"};
    const f = fixture({body: [item]});
    const [result] = await fetchDocumentVersions({...options, fetchImpl: f.fetchImpl});
    assert.equal(result.failureStage, "ORIGINAL_DOWNLOAD");
    assert.equal(result.failureCode, "ACCESS_DENIED");
    assert.equal(result.failedAt, item.failedAt);
    assert.notEqual(result, item);
});

test("legacy or malformed failure details do not break history and absent fields normalize to null", async () => {
    for (const fields of [{}, {failureStage: null, failureCode: null, failedAt: null},
        {failureStage: {}, failureCode: 3, failedAt: "bad"}, {failureStage: "", failureCode: " ", failedAt: 42}]) {
        const f = fixture({body: [{...version(), conversionStatus: "FAILED", ...fields}]});
        const [result] = await fetchDocumentVersions({...options, fetchImpl: f.fetchImpl});
        assert.equal(result.failureStage, null); assert.equal(result.failureCode, null); assert.equal(result.failedAt, null);
    }
});

test("accepts future failure codes while discarding a malformed failure time", async () => {
    const f = fixture({body: [{...version(), conversionStatus: "FAILED", failureStage: "FUTURE_STAGE",
        failureCode: "FUTURE_CODE", failedAt: "not-an-ISO-date"}]});
    const [result] = await fetchDocumentVersions({...options, fetchImpl: f.fetchImpl});
    assert.equal(result.failureStage, "FUTURE_STAGE"); assert.equal(result.failureCode, "FUTURE_CODE");
    assert.equal(result.failedAt, null);
});

test("does not expose stale failure metadata for pending, processing, completed or missing jobs", async () => {
    for (const conversionStatus of ["PENDING", "PROCESSING", "COMPLETED", null]) {
        const item = {...version(), conversionStatus, failureStage: "PDF_UPLOAD", failureCode: "ACCESS_DENIED",
            failedAt: "2026-11-22T12:34:56"};
        const f = fixture({body: [item]});
        const [result] = await fetchDocumentVersions({...options, fetchImpl: f.fetchImpl});
        assert.equal(result.failureStage, null); assert.equal(result.failureCode, null); assert.equal(result.failedAt, null);
        assert.equal(item.failureCode, "ACCESS_DENIED");
    }
});
