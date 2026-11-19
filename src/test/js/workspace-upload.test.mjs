import test from "node:test";
import assert from "node:assert/strict";
import {uploadWorkspaceFile, completeWorkspaceUpload, UploadCompletionError}
    from "../../main/resources/static/js/workspace-upload.mjs";

const file = {name: "기획서.docx", size: 1024};
const me = {id: 42, csrfToken: "test-csrf"};
const upload = {uploadId: 501, uploadUrl: "https://example.invalid/upload?signature=test", storageKey: "workspaces/1411/uploads/test"};
const refreshedMe = {id: me.id, csrfToken: "fresh-csrf"};
const completion = {uploadId: upload.uploadId, documentId: 601, documentVersionId: 701};
const completionPath = "/api/v1/uploads/" + upload.uploadId + "/complete";
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
    const mock = setup([json(me), json(upload), json(null), json(refreshedMe), json(completion)]);
    const progress = [];
    assert.deepEqual(await send(mock, {onProgress: text => progress.push(text)}),
        {...completion, storageKey: upload.storageKey});
    assert.equal(mock.calls.length, 5);
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
    assert.equal(mock.calls[3].url, "/api/v1/auth/me");
    assert.equal(mock.calls[3].options.cache, "no-store");
    const registration = mock.calls[4];
    assert.equal(registration.url, completionPath);
    assert.equal(registration.options.method, "POST");
    assert.equal(registration.options.credentials, "same-origin");
    assert.equal(registration.options.headers["X-CSRF-TOKEN"], refreshedMe.csrfToken);
    assert.equal(registration.options.body, undefined);
    assert.equal(progress.length, 4);
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
    const mock = setup([json(me), json(upload), json(null), json(refreshedMe), json(completion)]);
    await send(mock, {file: {name: "기획서.DOCX", size: 20 * 1024 * 1024}});
    assert.equal(mock.calls.length, 5);
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

test("upload promise remains pending until Document registration responds", async () => {
    let releaseCompletion;
    const waiting = new Promise(resolve => { releaseCompletion = resolve; });
    const mock = setup([json(me), json(upload), json(null), json(refreshedMe), waiting]);
    let resolved = false;
    const sending = send(mock).then(result => { resolved = true; return result; });
    try {
        await new Promise(resolve => setImmediate(resolve));
        assert.equal(mock.calls.length, 5);
        assert.equal(resolved, false);
    } finally {
        releaseCompletion(json(completion));
    }
    assert.deepEqual(await sending, {...completion, storageKey: upload.storageKey});
});

test("invalid issued uploadId stops before sending file bytes to S3", async t => {
    for (const uploadId of [undefined, 0, -1, "501", Number.MAX_SAFE_INTEGER + 1]) {
        await t.test(String(uploadId), async () => {
            const mock = setup([json(me), json({...upload, uploadId})]);
            await assert.rejects(send(mock), /업로드 요청을 확인/);
            assert.equal(mock.calls.length, 2);
        });
    }
});

test("expired login after S3 PUT preserves uploadId and stops registration", async () => {
    const mock = setup([json(me), json(upload), json(null), json(null, 401)]);
    await assert.rejects(send(mock), error => {
        assert.ok(error instanceof UploadCompletionError);
        assert.equal(error.uploadId, upload.uploadId);
        assert.equal(error.status, 401);
        assert.equal(error.canRetry, true);
        assert.equal(error.storageKey, upload.storageKey);
        assert.equal(error.fileName, file.name);
        assert.match(error.message, /파일은 업로드됐지만 문서 등록을 확인하지 못/);
        return true;
    });
    assert.equal(mock.calls.length, 4);
});

test("changed login after S3 PUT does not register under another account", async () => {
    const mock = setup([json(me), json(upload), json(null), json({...refreshedMe, id: 99})]);
    await assert.rejects(send(mock), error =>
        error instanceof UploadCompletionError && /로그인 계정이 바뀌/.test(error.message));
    assert.equal(mock.calls.length, 4);
});

test("missing refreshed CSRF token stops after S3 PUT without registration", async () => {
    const mock = setup([json(me), json(upload), json(null), json({id: me.id})]);
    await assert.rejects(send(mock), error =>
        error instanceof UploadCompletionError && /보안 토큰/.test(error.message));
    assert.equal(mock.calls.length, 4);
});

test("completion API failures preserve uploadId and never automatically resend", async t => {
    for (const status of [401, 403, 404, 409, 410, 502, 503]) {
        await t.test(String(status), async () => {
            const mock = setup([json(me), json(upload), json(null), json(refreshedMe), json(null, status)]);
            await assert.rejects(send(mock), error => {
                assert.ok(error instanceof UploadCompletionError);
                assert.equal(error.uploadId, upload.uploadId);
                assert.equal(error.status, status);
                assert.equal(error.canRetry, [401, 403, 502, 503].includes(status));
                if (status === 404) {
                    assert.match(error.message, /업로드 요청 또는 업로드한 파일/);
                    assert.doesNotMatch(error.message, /워크스페이스를 찾을 수/);
                }
                return true;
            });
            assert.equal(mock.calls.length, 5);
        });
    }
});

test("ambiguous registration failure retries the same uploadId without another S3 PUT", async () => {
    const mock = setup([
        json(me), json(upload), json(null), json(refreshedMe),
        new TypeError("Failed to fetch"), json(refreshedMe), json(completion)
    ]);
    let failure;
    await assert.rejects(send(mock), error => {
        failure = error;
        assert.ok(error instanceof UploadCompletionError);
        assert.equal(error.canRetry, true);
        assert.equal(error.uploadId, upload.uploadId);
        return true;
    });
    assert.equal(mock.calls.length, 5);

    const result = await completeWorkspaceUpload({
        uploadId: failure.uploadId, expectedUserId: me.id, fetchImpl: mock.fetchImpl
    });

    assert.deepEqual(result, completion);
    assert.equal(mock.calls.length, 7);
    assert.equal(mock.calls[5].url, "/api/v1/auth/me");
    assert.equal(mock.calls[6].url, completionPath);
    assert.equal(mock.calls[6].options.method, "POST");
    assert.equal(mock.calls[6].options.body, undefined);
    assert.equal(mock.calls.filter(call => call.options.method === "PUT").length, 1);
    assert.equal(mock.calls.filter(call => call.url === "/api/v1/workspaces/1411/uploads").length, 1);
});

test("invalid registration responses are not reported as success", async t => {
    for (const [name, response] of [
        ["empty", null],
        ["wrong uploadId", {...completion, uploadId: 999}],
        ["missing documentId", {uploadId: upload.uploadId, documentVersionId: 701}],
        ["invalid documentVersionId", {...completion, documentVersionId: 0}]
    ]) {
        await t.test(name, async () => {
            const mock = setup([json(me), json(upload), json(null), json(refreshedMe), json(response)]);
            await assert.rejects(send(mock), error => {
                assert.ok(error instanceof UploadCompletionError);
                assert.equal(error.uploadId, upload.uploadId);
                assert.equal(error.canRetry, true);
                assert.match(error.message, /문서 등록 응답을 확인/);
                return true;
            });
            assert.equal(mock.calls.length, 5);
        });
    }
});

test("unreadable completion JSON preserves uploadId for checking an uncertain result", async () => {
    const unreadable = {
        ok: true, status: 200,
        json: async () => { throw new SyntaxError("Unexpected token"); }
    };
    const mock = setup([json(me), json(upload), json(null), json(refreshedMe), unreadable]);
    await assert.rejects(send(mock), error => {
        assert.ok(error instanceof UploadCompletionError);
        assert.equal(error.uploadId, upload.uploadId);
        assert.equal(error.canRetry, true);
        assert.match(error.message, /서버 응답을 확인할 수/);
        assert.doesNotMatch(error.message, /Unexpected token/);
        return true;
    });
    assert.equal(mock.calls.length, 5);
});

test("registration-only retry validates uploadId before making any request", async t => {
    for (const uploadId of [undefined, 0, "501"]) {
        await t.test(String(uploadId), async () => {
            const mock = setup([]);
            await assert.rejects(completeWorkspaceUpload({
                uploadId, expectedUserId: me.id, fetchImpl: mock.fetchImpl
            }), /업로드 요청을 확인/);
            assert.equal(mock.calls.length, 0);
        });
    }
});
