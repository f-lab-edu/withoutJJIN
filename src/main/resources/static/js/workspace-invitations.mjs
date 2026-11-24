import {createInvitationClient, invitationIsActive, invitationTime, invitationUrl} from "./shared-invitations.mjs?v=20261125-1";

export function createWorkspaceInvitationsController({client = createInvitationClient(), view, origin,
    clipboard, now = Date.now} = {}) {
    const state = {user: null, workspaces: [], selected: null, owner: false, invitations: [], link: "", busy: false, message: "", error: false};
    let epoch = 0, linkEpoch = 0, stopped = false;
    const emit = () => view({...state, workspaces: [...state.workspaces], invitations: [...state.invitations]});
    function clearLink() { linkEpoch++; state.link = ""; emit(); }
    function reset() {
        state.user = null; state.workspaces = []; state.selected = null;
        state.owner = false; state.invitations = []; clearLink();
    }
    async function run(action) {
        if (state.busy || stopped) return;
        const version = ++epoch;
        const current = () => !stopped && version === epoch;
        state.busy = true; state.message = ""; emit();
        try { await action(current); }
        catch (error) {
            if (current()) {
                if (error.status === 401) reset();
                if ([403, 404, 410].includes(error.status)) { state.link = ""; state.invitations = []; }
                state.error = true;
                state.message = error.message || "처리하지 못했어요. 다시 시도해 주세요.";
            }
        } finally { if (current()) { state.busy = false; emit(); } }
    }
    async function loadSelected(id, current) {
        state.selected = state.workspaces.find(item => item.id === id) ?? null;
        state.invitations = []; state.owner = false; clearLink();
        if (!state.selected) return;
        const userId = state.user.id;
        const detail = await client.request(`/api/v1/workspaces/${id}`);
        if (!current()) return;
        await client.currentUser(userId);
        if (!current()) return;
        state.owner = detail.id === id && detail.createdBy === userId;
        if (state.owner) {
            const invitations = await client.list(id, userId);
            if (current()) state.invitations = invitations;
        } else {
            state.message = "워크스페이스 생성자만 초대 URL을 생성·복사·폐기할 수 있어요.";
            state.error = false;
        }
    }
    async function loadWorkspaces(preferredId, current) {
        const result = await client.request("/api/v1/workspaces");
        await client.currentUser(state.user.id);
        if (!current()) return;
        if (!Array.isArray(result)) throw new Error("워크스페이스 목록을 확인하지 못했어요.");
        state.workspaces = result;
        await loadSelected(result.find(item => item.id === preferredId)?.id ?? result[0]?.id, current);
    }
    function requireOwner() {
        if (!state.owner || !state.selected) throw new Error("워크스페이스 생성자만 초대 URL을 관리할 수 있어요.");
    }
    return {
        clearLink,
        start(preferredId) { return run(async current => {
            state.user = await client.me();
            if (!state.user) { reset(); return; }
            await loadWorkspaces(preferredId, current);
        }); },
        select(id) { return run(current => loadSelected(id, current)); },
        refresh() { return run(current => loadWorkspaces(state.selected?.id, current)); },
        create() { return run(async current => {
            requireOwner(); const visible = linkEpoch;
            const result = await client.create(state.selected.id, state.user.id);
            if (!current()) return;
            state.invitations.unshift(result.invitation);
            if (visible === linkEpoch) state.link = invitationUrl(result.token, origin);
            state.error = false; state.message = "7일간 사용할 초대 URL을 만들었어요. URL을 복사해 공유해 주세요.";
        }); },
        copy(id) { return run(async current => {
            requireOwner(); state.link = ""; const visible = linkEpoch;
            const result = await client.get(state.selected.id, id, state.user.id);
            if (!current() || visible !== linkEpoch) return;
            if (!invitationIsActive(result.invitation, now())) throw new Error("만료되었거나 폐기된 URL이에요. 목록을 새로고침해 주세요.");
            state.link = invitationUrl(result.token, origin);
            try {
                if (!clipboard?.writeText) throw new Error();
                await clipboard.writeText(state.link);
                if (current()) { state.error = false; state.message = "초대 URL을 복사했어요. 만료 시각은 그대로 유지돼요."; }
            } catch (_) {
                if (current()) { state.error = true; state.message = "자동 복사를 사용할 수 없어요. 표시된 URL을 선택해 직접 복사해 주세요."; }
            }
        }); },
        revoke(id) { return run(async current => {
            requireOwner(); clearLink();
            await client.revoke(state.selected.id, id, state.user.id);
            if (!current()) return;
            state.invitations = await client.list(state.selected.id, state.user.id);
            state.error = false; state.message = "초대 URL을 폐기했어요. 이미 가입한 멤버는 유지돼요.";
        }); },
        createWorkspace(name) { return run(async current => {
            if (!name.trim()) throw new Error("워크스페이스 이름을 입력해 주세요.");
            const result = await client.createWorkspace(name.trim(), state.user?.id);
            if (current()) await loadWorkspaces(result.id, current);
        }); },
        logout() { return run(async current => {
            await client.logout(state.user.id);
            if (current()) { reset(); state.error = false; state.message = "로그아웃했어요."; }
        }); },
        stop() { stopped = true; epoch++; state.link = ""; state.user = null; emit(); }
    };
}

export function initializeWorkspaceInvitations() {
    const $ = id => document.getElementById(id);
    const element = (tag, className, text) => {
        const node = document.createElement(tag); node.className = className;
        if (text !== undefined) node.textContent = text;
        return node;
    };
    const format = value => Number.isFinite(invitationTime(value))
        ? new Date(invitationTime(value)).toLocaleString("ko-KR", {timeZone: "Asia/Seoul"}) + " (한국 시간)" : "—";
    let controller;
    function render(state) {
        $("feedback").textContent = state.message; $("feedback").hidden = !state.message;
        $("feedback").className = "feedback" + (state.error ? " error" : "");
        $("login").hidden = !!state.user; $("logout").hidden = !state.user;
        $("logout").disabled = state.busy;
        $("account-label").textContent = state.user?.email || "로그인 전";
        $("session-badge").textContent = state.user ? "로그인됨" : "로그인 필요";
        $("notice-title").textContent = state.user ? "팀원을 URL로 초대하세요." : "Google 로그인으로 시작하세요.";
        $("notice-copy").textContent = "생성자가 공유한 초대 URL에서 Google 로그인 후 참여할 수 있어요.";
        for (const [id, done] of [["step-login", state.user], ["step-team", state.selected], ["step-invite", state.invitations.length]]) $(id).classList.toggle("done", !!done);
        for (const id of ["workspace-name", "create-workspace", "refresh-workspaces"]) $(id).disabled = !state.user || state.busy;
        $("create-invitation").disabled = !state.owner || state.busy;
        $("refresh-invitations").disabled = !state.owner || state.busy;
        $("invite-policy").textContent = state.selected && !state.owner
            ? "워크스페이스 생성자만 초대 URL을 관리할 수 있어요." : "같은 URL을 7일간 다시 조회·복사할 수 있어요. 조회하거나 참여해도 만료 시각은 바뀌지 않아요.";
        $("selected-team").textContent = state.selected?.name || "왼쪽에서 팀을 선택하세요.";
        $("open-workspace").hidden = !state.selected;
        if (state.selected) $("open-workspace").href = `/workspaces/${state.selected.id}`;
        else $("open-workspace").removeAttribute("href");
        $("share-link-panel").hidden = !state.link;
        $("share-link").value = state.link;
        $("workspaces").replaceChildren(...(state.workspaces.length ? state.workspaces.map(item => {
            const button = element("button", "workspace" + (state.selected?.id === item.id ? " selected" : ""));
            button.type = "button"; button.disabled = state.busy;
            button.setAttribute("aria-pressed", String(state.selected?.id === item.id));
            const info = element("div", ""); info.append(element("div", "workspace-name", item.name), element("div", "workspace-id", `워크스페이스 #${item.id}`));
            button.append(element("span", "folder", "▱"), info);
            button.addEventListener("click", () => controller.select(item.id)); return button;
        }) : [element("p", "empty", state.user ? "소속된 팀이 없어요. 새 워크스페이스를 만들어 보세요." : "로그인하면 내 워크스페이스가 표시돼요.")]));
        $("invitations").replaceChildren(...(state.invitations.length ? state.invitations.map(item => {
            const card = element("article", "invitation");
            const top = element("div", "invitation-top");
            const active = invitationIsActive(item);
            top.append(element("strong", "recipient", `공유 초대 #${item.id}`), element("span", "badge" + (active ? " green" : ""),
                item.invitationStatus === "CANCELED" ? "폐기됨" : active ? "사용 가능" : "만료됨"));
            const actions = element("div", "invitation-footer");
            const copy = element("button", "button quiet", "URL 복사");
            copy.type = "button"; copy.disabled = state.busy || !active;
            copy.addEventListener("click", () => controller.copy(item.id));
            const revoke = element("button", "button quiet", "폐기");
            revoke.type = "button"; revoke.disabled = state.busy || item.invitationStatus === "CANCELED";
            revoke.addEventListener("click", () => {
                if (confirm(`공유 초대 #${item.id}을 폐기할까요? 이 URL로 새로 참여할 수 없게 돼요. 이미 가입한 멤버는 유지돼요.`)) controller.revoke(item.id);
            });
            actions.append(copy, revoke);
            card.append(top, element("p", "invitation-copy", `만료 ${format(item.expiresAt)}`), actions); return card;
        }) : [element("p", "empty", state.owner ? "생성한 공유 초대 URL이 없어요." : "생성자에게 초대 URL을 요청해 주세요.")]));
    }
    controller = createWorkspaceInvitationsController({view: render, origin: location.origin, clipboard: navigator.clipboard});
    $("invite-form").addEventListener("submit", event => {event.preventDefault(); controller.create();});
    $("workspace-form").addEventListener("submit", event => {event.preventDefault(); controller.createWorkspace($("workspace-name").value);});
    for (const id of ["refresh-workspaces", "refresh-invitations"]) $(id).addEventListener("click", () => controller.refresh());
    $("logout").addEventListener("click", () => controller.logout());
    $("share-link").addEventListener("click", () => $("share-link").select());
    document.addEventListener("visibilitychange", () => {if (document.hidden) controller.clearLink();});
    window.addEventListener("pagehide", () => controller.stop());
    window.addEventListener("pageshow", event => {if (event.persisted) location.reload();});
    controller.start(Number(new URLSearchParams(location.search).get("workspace")));
}
