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

async function requestJson(fetchImpl, path, options = {}, errorMessages) {
    let response;
    try {
        response = await fetchImpl(path, {credentials: "same-origin", cache: "no-store", ...options});
    } catch (_) {
        throw new Error("서버에 연결하지 못했어요. 연결을 확인해 주세요.");
    }
    if (!response.ok) {
        const messages = errorMessages || {
            400: "파일 이름·크기·형식을 확인해 주세요.",
            401: "로그인이 만료됐어요. 다시 로그인해 주세요.",
            403: "업로드 권한 또는 보안 토큰을 확인할 수 없어요. 화면을 새로고침해 주세요.",
            404: "워크스페이스를 찾을 수 없어요."
        };
        const fallback = errorMessages
            ? "문서 등록에 실패했어요. 잠시 뒤 등록을 다시 시도해 주세요."
            : "업로드 준비에 실패했어요. 잠시 뒤 다시 시도해 주세요.";
        const error = new Error(messages[response.status] || fallback);
        error.status = response.status;
        throw error;
    }
    try {
        return await response.json();
    } catch (cause) {
        throw new Error("서버 응답을 확인할 수 없어요.", {cause});
    }
}

// 우리 서버는 권한을 확인하고 URL을 발급한다. 실제 파일 본문은 S3로 보낸다.
export async function uploadWorkspaceFile({workspaceId, file, expectedUserId,
    fetchImpl = fetch, onProgress = () => {}}) {
    validateUploadFile(file);
    if (!Number.isSafeInteger(workspaceId) || workspaceId <= 0) {
        throw new Error("워크스페이스 주소를 확인해 주세요.");
    }
    onProgress("로그인 상태와 업로드 권한을 확인하고 있어요.");
    const me = await requireCurrentUser(fetchImpl, expectedUserId);

    const upload = await requestJson(fetchImpl, `/api/v1/workspaces/${workspaceId}/uploads`, {
        method: "POST",
        headers: {"Content-Type": "application/json", "X-CSRF-TOKEN": me.csrfToken},
        body: JSON.stringify({fileName: file.name, fileSize: file.size, contentType: DOCX_CONTENT_TYPE})
    });

    validateUploadId(upload.uploadId);
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
    try {
        const completion = await completeWorkspaceUpload({
            uploadId: upload.uploadId, expectedUserId, fetchImpl, onProgress
        });
        return {...completion, storageKey: upload.storageKey};
    } catch (error) {
        if (error instanceof UploadCompletionError) {
            error.storageKey = upload.storageKey;
            error.fileName = file.name;
        }
        throw error;
    }
}


async function requireCurrentUser(fetchImpl, expectedUserId) {
    const me = await requestJson(fetchImpl, "/api/v1/auth/me");
    if (!me || me.id !== expectedUserId) {
        throw new Error("다른 탭에서 로그인 계정이 바뀌었어요. 화면을 새로고침해 주세요.");
    }
    if (!me.csrfToken) throw new Error("보안 토큰을 확인할 수 없어요. 화면을 새로고침해 주세요.");
    return me;
}

function validateUploadId(uploadId) {
    if (!Number.isSafeInteger(uploadId) || uploadId <= 0) {
        throw new Error("업로드 요청을 확인할 수 없어요. 파일을 다시 선택해 주세요.");
    }
}

// S3 PUT 성공 후 Document 등록만 실패한 경우의 재시도 정보다.
export class UploadCompletionError extends Error {
    constructor(uploadId, cause) {
        const detail = cause instanceof Error ? cause.message : "잠시 뒤 등록을 다시 시도해 주세요.";
        super("파일은 업로드됐지만 문서 등록을 확인하지 못했어요. " + detail, {cause});
        this.name = "UploadCompletionError";
        this.uploadId = uploadId;
        this.status = cause?.status;
        this.canRetry = !Number.isInteger(this.status)
            || this.status === 401 || this.status === 403 || this.status >= 500;
    }
}

const COMPLETION_MESSAGES = {
    400: "문서 등록 요청을 확인할 수 없어요.",
    401: "로그인이 만료됐어요. 다시 로그인해 주세요.",
    403: "문서 등록 권한 또는 보안 토큰을 확인할 수 없어요.",
    404: "업로드 요청 또는 업로드한 파일을 찾을 수 없어요.",
    409: "파일 정보 또는 업로드 상태를 확인해 주세요.",
    410: "업로드 요청의 유효기간이 지났어요. 파일을 다시 업로드해 주세요.",
    500: "문서 등록 중 오류가 발생했어요. 잠시 뒤 등록을 다시 시도해 주세요.",
    502: "업로드한 파일을 확인하지 못했어요. 잠시 뒤 등록을 다시 시도해 주세요.",
    503: "서버에 일시적으로 연결하지 못했어요. 잠시 뒤 등록을 다시 시도해 주세요."
};

// 재시도할 때도 같은 uploadId로 완료 API만 호출하고 S3 PUT은 실행하지 않는다.
export async function completeWorkspaceUpload({
    uploadId, expectedUserId, fetchImpl = fetch, onProgress = () => {}
}) {
    validateUploadId(uploadId);
    try {
        onProgress("문서 등록을 위해 로그인 상태를 다시 확인하고 있어요.");
        const me = await requireCurrentUser(fetchImpl, expectedUserId);
        onProgress("파일 업로드를 확인하고 문서를 등록하고 있어요.");
        const completion = await requestJson(
            fetchImpl, "/api/v1/uploads/" + uploadId + "/complete", {
                method: "POST", headers: {"X-CSRF-TOKEN": me.csrfToken}
            }, COMPLETION_MESSAGES);
        if (!completion || completion.uploadId !== uploadId
                || !Number.isSafeInteger(completion.documentId) || completion.documentId <= 0
                || !Number.isSafeInteger(completion.documentVersionId) || completion.documentVersionId <= 0) {
            throw new Error("문서 등록 응답을 확인할 수 없어요. 등록을 다시 확인해 주세요.");
        }
        return {
            uploadId: completion.uploadId,
            documentId: completion.documentId,
            documentVersionId: completion.documentVersionId
        };
    } catch (cause) {
        throw new UploadCompletionError(uploadId, cause);
    }
}
