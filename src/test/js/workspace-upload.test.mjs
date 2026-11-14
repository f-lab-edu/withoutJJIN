import test from "node:test";
import assert from "node:assert/strict";
import {uploadWorkspaceFile} from "../../main/resources/static/js/workspace-upload.mjs";

const file = {name: "기획서.docx", size: 1024};
const me = {id: 42, csrfToken: "test-csrf"};
const upload = {uploadUrl: "https://example.invalid/upload?signature=test", storageKey: "workspaces/1411/uploads/test"};
const json = (value, status = 200) => ({ok: status >= 200 && status < 300, status, json: async () => value});

function setup(responses) {
    const calls = [];
    return {
        calls,
        fetchImpl: async (url, options) => {
            calls.push({url, options});
            assert.ok(responses.length, "Unexpected extra request");
            const next = responses.shift();
            if (next instanceof Error) throw next;
            return next;
        }
    };
}

function send(mock, extra = {}) {
    return uploadWorkspaceFile({workspaceId: 1411, file, expectedUserId: 42, fetchImpl: mock.fetchImpl, ...extra});
}

test("uploads the selected file only after authenticated URL issuance", async () => {
    const mock = setup([json(me), json(upload), json(null)]);
    const progress = [];
    assert.deepEqual(await send(mock, {onProgress: text => progress.push(text)}), {storageKey: upload.storageKey});
    assert.equal(mock.calls.length, 3);
    assert.equal(mock.calls[0].url, "/api/v1/auth/me");
    assert.equal(mock.calls[0].options.credentials, "same-origin");
    const issuance = mock.calls[1];
    assert.equal(issuance.url, "/api/v1/workspaces/1411/uploads");
    assert.equal(issuance.options.method, "POST");
    assert.equal(issuance.options.headers["X-CSRF-TOKEN"], me.csrfToken);
    assert.deepEqual(JSON.parse(issuance.options.body), {
        fileName: file.name, fileSize: file.size,
        contentType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    });
    const put = mock.calls[2];
    assert.equal(put.url, upload.uploadUrl);
    assert.equal(put.options.method, "PUT");
    assert.equal(put.options.body, file);
    assert.equal(put.options.credentials, "omit");
    assert.equal(put.options.headers["X-CSRF-TOKEN"], undefined);
    assert.equal(put.options.headers["If-None-Match"], "*");
    assert.deepEqual(Object.keys(put.options.headers), ["Content-Type", "If-None-Match"]);
    assert.equal(progress.length, 2);
});

test("invalid files cause no URL issuance or S3 request", async t => {
    for (const invalid of [undefined, {name: "a.pdf", size: 10}, {name: "a.docx.exe", size: 10},
        {name: "a.docx", size: 0}, {name: "a.docx", size: 20 * 1024 * 1024 + 1},
        {name: "a".repeat(251) + ".docx", size: 1}]) {
        await t.test(String(invalid?.name ?? "missing"), async () => {
            const mock = setup([]);
            await assert.rejects(send(mock, {file: invalid}));
            assert.equal(mock.calls.length, 0);
        });
    }
});

test("accepts the maximum size and uppercase DOCX extension", async () => {
    const mock = setup([json(me), json(upload), json(null)]);
    await send(mock, {file: {name: "기획서.DOCX", size: 20 * 1024 * 1024}});
    assert.equal(mock.calls.length, 3);
});

test("expired login stops before URL issuance", async () => {
    const mock = setup([json(null, 401)]);
    await assert.rejects(send(mock), error => error.status === 401);
    assert.equal(mock.calls.length, 1);
});

test("changed login account stops before URL issuance", async () => {
    const mock = setup([json({...me, id: 99})]);
    await assert.rejects(send(mock), /로그인 계정이 바뀌/);
    assert.equal(mock.calls.length, 1);
});

test("missing CSRF token stops before URL issuance", async () => {
    const mock = setup([json({id: me.id})]);
    await assert.rejects(send(mock), /보안 토큰/);
    assert.equal(mock.calls.length, 1);
});

test("rejected membership or CSRF causes no S3 request", async () => {
    const mock = setup([json(me), json(null, 403)]);
    await assert.rejects(send(mock), error => error.status === 403);
    assert.equal(mock.calls.length, 2);
});

test("S3 rejection is not reported as upload success", async () => {
    const mock = setup([json(me), json(upload), json(null, 403)]);
    await assert.rejects(send(mock), /HTTP 403/);
    assert.equal(mock.calls.length, 3);
});

test("ambiguous network failure does not automatically resend the file", async () => {
    const mock = setup([json(me), json(upload), new TypeError("Failed to fetch")]);
    await assert.rejects(send(mock), /결과를 확인하지 못/);
    assert.equal(mock.calls.length, 3);
});


test("S3 precondition failure is not reported as upload success or retried", async () => {
    const mock = setup([json(me), json(upload), json(null, 412)]);
    await assert.rejects(send(mock), error =>
        error.message.includes("이미 사용한 업로드 주소")
        && error.message.includes("HTTP 412"));
    assert.equal(mock.calls.length, 3);
});

test("S3 concurrent conflict does not automatically resend the file", async () => {
    const mock = setup([json(me), json(upload), json(null, 409)]);
    await assert.rejects(send(mock), /HTTP 409/);
    assert.equal(mock.calls.length, 3);
});
