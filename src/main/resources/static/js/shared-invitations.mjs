const WEEK = 7 * 24 * 60 * 60 * 1000;
const STORAGE_KEY = "ftfm-pending-invitation";
const positiveId = value => Number.isSafeInteger(value) && value > 0;
export const validInvitationToken = value => typeof value === "string" && /^[A-Za-z0-9_-]{43}$/.test(value);

export function invitationTime(value) {
    if (typeof value !== "string" || !value) return NaN;
    return Date.parse(value + (/(?:Z|[+-]\d\d:\d\d)$/i.test(value) ? "" : "+09:00"));
}

export function invitationIsActive(invitation, now = Date.now()) {
    return invitation?.invitationStatus === "ACTIVE" && !invitation.revokedAt
        && invitationTime(invitation.expiresAt) > now;
}

export function invitationUrl(token, origin) {
    if (!validInvitationToken(token)) throw new Error("초대 URL을 확인하지 못했어요.");
    const url = new URL("/invite", origin);
    if (!["http:", "https:"].includes(url.protocol)) throw new Error("서비스 주소를 확인해 주세요.");
    // fragment는 /invite HTML을 받는 HTTP 요청과 Referer에 포함되지 않는다.
    url.hash = new URLSearchParams({token}).toString();
    return url.href;
}

export class InvitationRequestError extends Error {
    constructor(message, status = 0) { super(message); this.name = "InvitationRequestError"; this.status = status; }
}

const messages = {
    400: "입력값 또는 초대 URL을 확인해 주세요.",
    401: "로그인이 만료됐어요. 다시 로그인해 주세요.",
    403: "초대 URL 관리 권한 또는 로그인 상태를 확인해 주세요.",
    404: "워크스페이스 또는 초대 URL을 찾을 수 없어요.",
    410: "만료되었거나 폐기된 초대 URL이에요. 생성자에게 새 URL을 요청해 주세요.",
    429: "요청이 많아요. 잠시 뒤 다시 시도해 주세요."
};

export function createInvitationClient({fetchImpl = globalThis.fetch} = {}) {
    async function request(path, options = {}) {
        let response;
        try {
            response = await fetchImpl(path, {...options, credentials: "same-origin", cache: "no-store", referrerPolicy: "no-referrer"});
        } catch (_) { throw new InvitationRequestError("서버에 연결하지 못했어요. 다시 시도해 주세요."); }
        // 원격 오류 본문이나 요청 URL에는 토큰이 포함될 수 있어 화면·로그에 사용하지 않는다.
        if (!response.ok) throw new InvitationRequestError(messages[response.status] || "요청을 처리하지 못했어요. 다시 시도해 주세요.", response.status);
        if (response.status === 204) return null;
        try { return await response.json(); }
        catch (_) { throw new InvitationRequestError("서버 응답을 확인하지 못했어요."); }
    }
    async function me() {
        try {
            const user = await request("/api/v1/auth/me");
            if (!positiveId(user?.id)) throw new InvitationRequestError("로그인 정보를 확인하지 못했어요.", 401);
            return user;
        } catch (error) { if (error.status === 401) return null; throw error; }
    }
    async function currentUser(expectedUserId) {
        const user = await me();
        if (!user) throw new InvitationRequestError(messages[401], 401);
        if (!positiveId(expectedUserId) || user.id !== expectedUserId) {
            throw new InvitationRequestError("로그인 계정이 바뀌었어요. 화면을 새로고침해 주세요.", 401);
        }
        return user;
    }
    async function authenticated(path, expectedUserId, method = "GET", body) {
        const user = await currentUser(expectedUserId);
        const headers = {};
        if (method !== "GET") {
            if (!user.csrfToken) throw new InvitationRequestError("인증 정보를 확인하지 못했어요. 다시 로그인해 주세요.", 401);
            headers["X-CSRF-TOKEN"] = user.csrfToken;
        }
        if (body !== undefined) headers["Content-Type"] = "application/json";
        const result = await request(path, {method, headers, ...(body === undefined ? {} : {body: JSON.stringify(body)})});
        // 응답을 기다리는 동안 다른 탭에서 계정이 바뀌어도 이전 화면에 토큰을 표시하지 않는다.
        if (method === "GET") await currentUser(expectedUserId);
        return result;
    }
    function base(workspaceId) {
        if (!positiveId(workspaceId)) throw new InvitationRequestError("워크스페이스를 선택해 주세요.");
        return `/api/v1/workspaces/${workspaceId}/invitations`;
    }
    function detailPath(workspaceId, id) {
        if (!positiveId(id)) throw new InvitationRequestError("초대 번호를 확인해 주세요.");
        return `${base(workspaceId)}/${id}`;
    }
    function metadata(value, workspaceId) {
        if (!positiveId(value?.id) || value.workspaceId !== workspaceId || !positiveId(value.invitedBy)
            || !["ACTIVE", "CANCELED"].includes(value.invitationStatus) || !Number.isFinite(invitationTime(value.expiresAt))) {
            throw new InvitationRequestError("초대 정보를 확인하지 못했어요.");
        }
        // 목록·화면 상태에 원문 토큰이나 암호문을 복사하지 않는다.
        return {id: value.id, workspaceId, invitedBy: value.invitedBy, invitationStatus: value.invitationStatus,
            expiresAt: value.expiresAt, createdAt: value.createdAt, revokedAt: value.revokedAt ?? null};
    }
    return {
        request, me, currentUser,
        async list(workspaceId, userId) {
            const result = await authenticated(base(workspaceId), userId);
            if (!Array.isArray(result)) throw new InvitationRequestError("초대 목록을 확인하지 못했어요.");
            return result.map(item => metadata(item, workspaceId));
        },
        async create(workspaceId, userId) {
            const result = await authenticated(base(workspaceId), userId, "POST");
            await currentUser(userId);
            if (!validInvitationToken(result?.token)) throw new InvitationRequestError("초대 URL을 확인하지 못했어요.");
            return {invitation: metadata(result, workspaceId), token: result.token};
        },
        async get(workspaceId, id, userId) {
            const result = await authenticated(detailPath(workspaceId, id), userId);
            if (result?.id !== id || !validInvitationToken(result?.token)) throw new InvitationRequestError("초대 URL을 확인하지 못했어요.");
            return {invitation: metadata(result, workspaceId), token: result.token};
        },
        revoke(workspaceId, id, userId) { return authenticated(detailPath(workspaceId, id), userId, "DELETE"); },
        async preview(token) {
            if (!validInvitationToken(token)) throw new InvitationRequestError(messages[400], 400);
            const result = await request("/api/v1/invitations?token=" + encodeURIComponent(token));
            if (!positiveId(result?.workspaceId) || typeof result.workspaceName !== "string"
                || result.invitationStatus !== "ACTIVE" || !Number.isFinite(invitationTime(result.expiresAt))) {
                throw new InvitationRequestError("초대 정보를 확인하지 못했어요.");
            }
            return result;
        },
        async accept(token, userId) {
            if (!validInvitationToken(token)) throw new InvitationRequestError(messages[400], 400);
            const result = await authenticated("/api/v1/invitations/accept", userId, "POST", {token});
            if (!positiveId(result?.workspaceId) || typeof result.workspaceName !== "string") {
                throw new InvitationRequestError("가입 결과를 확인하지 못했어요. 내 워크스페이스 목록을 확인해 주세요.");
            }
            return result;
        },
        async logout(userId) {
            return authenticated("/api/v1/auth/logout", userId, "POST");
        },
        async createWorkspace(name, userId) {
            return authenticated("/api/v1/workspaces", userId, "POST", {name});
        }
    };
}

export function createInvitationSession({storage, now = Date.now} = {}) {
    function clear() { try { storage.removeItem(STORAGE_KEY); } catch (_) {} }
    function read() {
        try {
            const value = JSON.parse(storage.getItem(STORAGE_KEY) || "null");
            const age = now() - value?.savedAt;
            if (validInvitationToken(value?.token) && age >= 0 && age < WEEK
                && (!value.expiresAt || invitationTime(value.expiresAt) > now())) return value;
        } catch (_) {}
        clear(); return null;
    }
    function save(value) {
        try { storage.setItem(STORAGE_KEY, JSON.stringify(value)); return true; }
        catch (_) { return false; }
    }
    return {
        clear,
        load({search = "", hash = ""}) {
            const query = new URLSearchParams(search);
            const fragment = new URLSearchParams(hash.replace(/^#/, ""));
            if (query.has("token") || fragment.has("token")) {
                clear();
                const values = [...query.getAll("token"), ...fragment.getAll("token")];
                if (values.length !== 1 || !validInvitationToken(values[0])) return {token: null, stored: false};
                const token = values[0];
                return {token, stored: save({token, savedAt: now()})};
            }
            const value = read();
            return {token: value?.token ?? null, stored: !!value};
        },
        setExpiry(token, expiresAt) {
            const value = read();
            if (value?.token === token && Number.isFinite(invitationTime(expiresAt))) save({...value, expiresAt});
        }
    };
}
