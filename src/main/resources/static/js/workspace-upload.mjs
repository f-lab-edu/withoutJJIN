const DOCX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
const MAX_FILE_SIZE = 20 * 1024 * 1024;

export function validateUploadFile(file) {
    if (!file) throw new Error("업로드할 파일을 선택해 주세요.");
    if (!file.name || file.name.length > 255 || !file.name.toLowerCase().endsWith(".docx")) {
        throw new Error("이름이 255자 이하인 .docx 파일을 선택해 주세요.");
    }
    if (file.size <= 0 || file.size > MAX_FILE_SIZE) {
        throw new Error("파일 크기는 0보다 크고 20MiB 이하여야 해요.");
    }
}

async function requestJson(fetchImpl, path, options = {}) {
    let response;
    try {
        response = await fetchImpl(path, {credentials: "same-origin", cache: "no-store", ...options});
    } catch (_) {
        throw new Error("서버에 연결하지 못했어요. 연결을 확인해 주세요.");
    }
    if (!response.ok) {
        const messages = {
            400: "파일 이름·크기·형식을 확인해 주세요.",
            401: "로그인이 만료됐어요. 다시 로그인해 주세요.",
            403: "업로드 권한 또는 보안 토큰을 확인할 수 없어요. 화면을 새로고침해 주세요.",
            404: "워크스페이스를 찾을 수 없어요."
        };
        const error = new Error(messages[response.status] || "업로드 준비에 실패했어요. 잠시 뒤 다시 시도해 주세요.");
        error.status = response.status;
        throw error;
    }
    return response.json();
}

// 우리 서버는 권한을 확인하고 URL을 발급한다. 실제 파일 본문은 S3로 보낸다.
export async function uploadWorkspaceFile({workspaceId, file, expectedUserId,
    fetchImpl = fetch, onProgress = () => {}}) {
    validateUploadFile(file);
    if (!Number.isSafeInteger(workspaceId) || workspaceId <= 0) {
        throw new Error("워크스페이스 주소를 확인해 주세요.");
    }
    onProgress("로그인 상태와 업로드 권한을 확인하고 있어요.");
    const me = await requestJson(fetchImpl, "/api/v1/auth/me");
    if (me.id !== expectedUserId) {
        throw new Error("다른 탭에서 로그인 계정이 바뀌었어요. 화면을 새로고침해 주세요.");
    }
    if (!me.csrfToken) throw new Error("보안 토큰을 확인할 수 없어요. 화면을 새로고침해 주세요.");

    const upload = await requestJson(fetchImpl, `/api/v1/workspaces/${workspaceId}/uploads`, {
        method: "POST",
        headers: {"Content-Type": "application/json", "X-CSRF-TOKEN": me.csrfToken},
        body: JSON.stringify({fileName: file.name, fileSize: file.size, contentType: DOCX_CONTENT_TYPE})
    });

    onProgress("파일을 업로드하고 있어요. 이 화면을 유지해 주세요.");
    let response;
    try {
        response = await fetchImpl(upload.uploadUrl, {
            method: "PUT", credentials: "omit",
            headers: {
                "Content-Type": DOCX_CONTENT_TYPE,
                "If-None-Match": "*"
            }, body: file
        });
    } catch (_) {
        // 응답을 받지 못해도 S3에 저장됐을 수 있으므로 자동 재전송하지 않는다.
        throw new Error("업로드 결과를 확인하지 못했어요. 네트워크 연결을 확인해 주세요.");
    }
    if (!response.ok) {
        if (response.status === 412) {
            throw new Error("이미 사용한 업로드 주소예요. 파일을 다시 선택해 새 업로드를 시작해 주세요. (HTTP 412)");
        }
        throw new Error(`파일을 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요. (HTTP ${response.status})`);
    }
    return {storageKey: upload.storageKey};
}
