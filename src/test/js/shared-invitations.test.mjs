import test from "node:test";
import assert from "node:assert/strict";
import {createInvitationClient, createInvitationSession, invitationUrl, invitationIsActive, invitationTime}
    from "../../main/resources/static/js/shared-invitations.mjs";

const token = "a".repeat(43), otherToken = "b".repeat(43);
const item = {id: 4, workspaceId: 3, invitedBy: 7, invitationStatus: "ACTIVE", createdAt: "2026-11-25T12:00:00", expiresAt: "2026-12-02T12:00:00"};
const now = Date.parse("2026-11-25T12:00:00+09:00");
function fixture(options = {}) {
    const calls = [];
    let meCount = 0;
    const client = createInvitationClient({fetchImpl: async (path, init) => {
        calls.push({path, init});
        if (path === "/api/v1/auth/me") {
            meCount++;
            return {ok: !options.loggedOut, status: options.loggedOut ? 401 : 200, json: async () => ({id: options.changedAfter && meCount >= options.changedAfter ? 8 : 7, csrfToken: options.noCsrf ? null : "fresh-csrf"})};
        }
        if (options.networkFailure) throw new Error("https://private.invalid/?token=" + token);
        const status = options.status ?? (init.method === "DELETE" ? 204 : 200);
        const body = options.body ?? (path.includes("/invitations?") ? {workspaceId: 3, workspaceName: "팀", invitationStatus: "ACTIVE", expiresAt: item.expiresAt}
            : path === "/api/v1/invitations/accept" ? {invitationId: 4, workspaceId: 3, workspaceName: "팀", invitationStatus: "ACTIVE"}
            : init.method === "GET" && path.endsWith("/invitations") ? [{...item, token, encryptedToken: "private"}]
            : {...item, token});
        return {ok: status < 400, status, json: async () => body};
    }});
    return {client, calls};
}

test("shared URL puts token in fragment and no token in server query", () => {
    const url = new URL(invitationUrl(token, "https://team.example/something"));
    assert.equal(url.origin, "https://team.example"); assert.equal(url.pathname, "/invite");
    assert.equal(url.search, ""); assert.equal(new URLSearchParams(url.hash.slice(1)).get("token"), token);
    assert.throws(() => invitationUrl("<script>", "https://team.example"));
    assert.throws(() => invitationUrl(token, "file:///tmp/test"));
});

test("exact expiry and revoked invitation cannot be copied", () => {
    const expiry = invitationTime(item.expiresAt);
    assert.equal(invitationIsActive(item, expiry - 1), true);
    assert.equal(invitationIsActive(item, expiry), false);
    assert.equal(invitationIsActive({...item, invitationStatus: "CANCELED"}, now), false);
    assert.equal(invitationIsActive({...item, revokedAt: item.createdAt}, now), false);
    assert.equal(invitationIsActive({...item, expiresAt: "bad"}, now), false);
});

test("list never exposes token, hash or ciphertext to manager state", async () => {
    const f = fixture(); const list = await f.client.list(3, 7);
    assert.deepEqual(Object.keys(list[0]).sort(), ["id", "workspaceId", "invitedBy", "invitationStatus", "createdAt", "expiresAt", "revokedAt"].sort());
    assert.equal(JSON.stringify(list).includes(token), false);
    assert.ok(f.calls.every(call => call.init.credentials === "same-origin" && call.init.cache === "no-store" && call.init.referrerPolicy === "no-referrer"));
});

test("create posts without target email and re-reads original token through detail API", async () => {
    const f = fixture();
    assert.equal((await f.client.create(3, 7)).token, token);
    assert.equal((await f.client.get(3, 4, 7)).token, token);
    const mutation = f.calls.find(call => call.init.method === "POST");
    assert.equal(mutation.path, "/api/v1/workspaces/3/invitations");
    assert.equal(mutation.init.body, undefined);
    assert.equal(mutation.init.headers["X-CSRF-TOKEN"], "fresh-csrf");
});

test("delete uses current CSRF and accepts 204 without JSON", async () => {
    const f = fixture(); assert.equal(await f.client.revoke(3, 4, 7), null);
    const mutation = f.calls.find(call => call.init.method === "DELETE");
    assert.equal(mutation.init.headers["X-CSRF-TOKEN"], "fresh-csrf");
});

test("preview only reads and acceptance explicitly posts token with session and CSRF", async () => {
    const f = fixture(); await f.client.preview(token);
    assert.ok(f.calls.every(call => (call.init.method ?? "GET") === "GET"));
    await f.client.accept(token, 7);
    const mutation = f.calls.find(call => call.path === "/api/v1/invitations/accept");
    assert.deepEqual(JSON.parse(mutation.init.body), {token});
    assert.equal(mutation.init.headers["X-CSRF-TOKEN"], "fresh-csrf");
});

for (const options of [{loggedOut: true}, {noCsrf: true}, {changedAfter: 1}]) {
    test("does not mutate when session, CSRF or expected account is invalid: " + JSON.stringify(options), async () => {
        const f = fixture(options); await assert.rejects(f.client.accept(token, 7));
        assert.equal(f.calls.some(call => call.init.method === "POST"), false);
    });
}

test("account change while detail response is in flight does not return raw token", async () => {
    const f = fixture({changedAfter: 2});
    await assert.rejects(f.client.get(3, 4, 7), error => error.status === 401 && !error.message.includes(token));
});

for (const status of [400, 401, 403, 404, 410, 500]) {
    test("sanitizes remote error body and reports HTTP " + status, async () => {
        const f = fixture({status, body: {message: token, detail: "secret"}});
        await assert.rejects(f.client.preview(token), error => error.status === status && !error.message.includes(token) && !error.message.includes("secret"));
    });
}

test("network errors never expose token-bearing URL", async () => {
    const f = fixture({networkFailure: true});
    await assert.rejects(f.client.preview(token), error => !error.message.includes(token) && !error.message.includes("private.invalid"));
});

test("rejects wrong workspace, invitation or malformed token in successful response", async () => {
    for (const body of [{...item, workspaceId: 9, token}, {...item, id: 99, token}, {...item, token: "bad"}]) {
        await assert.rejects(fixture({body}).client.get(3, 4, 7));
    }
});

function memory() {
    const data = new Map();
    return {data, getItem: key => data.get(key) ?? null, setItem: (key, value) => data.set(key, value), removeItem: key => data.delete(key)};
}

test("keeps token only in session storage for login round trip and clears on success", () => {
    const storage = memory(); const session = createInvitationSession({storage, now: () => now});
    assert.deepEqual(session.load({hash: "#token=" + token}), {token, stored: true});
    assert.deepEqual(session.load({search: "?loginError=LOGIN_CANCELED"}), {token, stored: true});
    session.clear(); assert.deepEqual(session.load({}), {token: null, stored: false});
});

test("expired session token is not restored and reads do not reset saved time", () => {
    const storage = memory(); let clock = now;
    const session = createInvitationSession({storage, now: () => clock});
    session.load({hash: "#token=" + token});
    clock += 6 * 86400000; assert.equal(session.load({}).token, token);
    clock += 86400000; assert.equal(session.load({}).token, null);
});

test("server expiry can shorten temporary storage lifetime", () => {
    const storage = memory(); let clock = now;
    const session = createInvitationSession({storage, now: () => clock});
    session.load({hash: "#token=" + token});
    session.setExpiry(token, "2026-11-26T12:00:00");
    clock += 86400000; assert.equal(session.load({}).token, null);
});

test("invalid or ambiguous explicit token clears previous token instead of restoring it", () => {
    for (const location of [{hash: "#token=bad"}, {hash: `#token=${token}&token=${otherToken}`}, {search: "?token=" + token, hash: "#token=" + otherToken}]) {
        const storage = memory(), session = createInvitationSession({storage, now: () => now});
        session.load({hash: "#token=" + token});
        assert.deepEqual(session.load(location), {token: null, stored: false});
        assert.equal(storage.data.size, 0);
    }
});

test("storage-disabled browser retains current URL token without crashing", () => {
    const storage = {getItem() {throw new Error();}, setItem() {throw new Error();}, removeItem() {throw new Error();}};
    const session = createInvitationSession({storage, now: () => now});
    assert.deepEqual(session.load({hash: "#token=" + token}), {token, stored: false});
    assert.deepEqual(session.load({}), {token: null, stored: false});
});
