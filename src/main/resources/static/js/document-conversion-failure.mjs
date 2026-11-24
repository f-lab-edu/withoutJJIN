const STAGES = new Map([
    ["ORIGINAL_DOWNLOAD", "원본 파일 읽기"], ["PDF_CONVERSION", "PDF 변환"],
    ["PDF_UPLOAD", "PDF 저장"], ["RESULT_SAVE", "변환 결과 반영"]
]);
const MESSAGES = new Map([
    ["ACCESS_DENIED", "파일 접근 권한 문제로 변환하지 못했어요. 서비스 운영자에게 문의해 주세요."],
    ["FILE_NOT_FOUND", "원본 파일을 찾을 수 없어요. 원본 파일을 새 버전으로 다시 올려 주세요."],
    ["CONNECTION_FAILED", "변환 처리 중 연결하지 못했어요. 잠시 후 다시 시도해 주세요."],
    ["TIMEOUT", "변환 처리 시간이 초과됐어요. 잠시 후 다시 시도해 주세요."],
    ["CONVERSION_FAILED", "Word 파일을 PDF로 변환하지 못했어요. 원본 파일을 확인해 주세요."],
    ["INVALID_PDF", "변환된 PDF를 확인하지 못했어요. 원본 파일을 확인해 주세요."],
    ["SIZE_LIMIT_EXCEEDED", "변환된 PDF가 허용 크기를 초과했어요. Word의 이미지 등을 줄여 새 버전으로 올려 주세요."],
    ["RESULT_SAVE_FAILED", "변환 결과를 저장하지 못했어요. 잠시 후 다시 시도해 주세요."]
]);

export function describeConversionFailure(version) {
    const time = version?.failedAt;
    const hasTime = typeof time === "string" && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?$/.test(time)
        && Number.isFinite(Date.parse(time));
    return {
        stageLabel: STAGES.get(version?.failureStage) || "실패 단계 정보 없음",
        message: MESSAGES.get(version?.failureCode) || "PDF 변환에 실패했어요. 원인을 확인한 뒤 다시 시도해 주세요.",
        failedAtLabel: hasTime ? "실패 시각 " + time.replace("T", " ").slice(0, 19) : "실패 시각 정보 없음"
    };
}
