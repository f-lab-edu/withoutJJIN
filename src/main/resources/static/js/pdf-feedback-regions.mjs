const COLORS = {
    green: {border: "#16785a", fill: "#39a87926"},
    yellow: {border: "#b7791f", fill: "#facc152e"},
    blue: {border: "#2563eb", fill: "#60a5fa2e"},
    pink: {border: "#be185d", fill: "#f472b62e"}
};

// 저장된 영역은 작성 중인 .pdf-selection과 별도로 관리한다.
export function createPdfFeedbackRegions({container}) {
    const regions = new Map();

    function clear() {
        for (const box of regions.values()) box.remove();
        regions.clear();
    }

    function setItems(values) {
        const pages = new Map(Array.from(container.querySelectorAll(".pdf-page"),
            page => [Number(page.dataset.pageNumber), page]));
        const ids = new Set();
        // 잘못된 좌표나 없는 페이지가 포함되면 일부 영역만 그리지 않는다.
        const prepared = values.map(value => {
            const page = pages.get(value.pageNumber);
            if (!Number.isSafeInteger(value.id) || value.id < 1 || ids.has(value.id)
                    || !page || page.dataset.ready !== "true"
                    || ![value.x, value.y, value.width, value.height].every(Number.isFinite)
                    || value.x < 0 || value.y < 0 || value.width <= 0 || value.height <= 0
                    || value.x + value.width > 1 || value.y + value.height > 1
                    || !Object.hasOwn(COLORS, value.color)) {
                throw new RangeError("PDF에 표시할 피드백 영역을 확인할 수 없습니다.");
            }
            ids.add(value.id);
            return {value, page};
        });
        clear();
        for (const {value, page} of prepared) {
            const box = container.ownerDocument.createElement("div");
            box.className = "pdf-feedback-region";
            box.dataset.feedbackId = String(value.id);
            box.dataset.active = "false";
            box.setAttribute("aria-hidden", "true");
            box.style.left = value.x * 100 + "%";
            box.style.top = value.y * 100 + "%";
            box.style.width = value.width * 100 + "%";
            box.style.height = value.height * 100 + "%";
            box.style.borderColor = COLORS[value.color].border;
            box.style.backgroundColor = COLORS[value.color].fill;
            page.append(box);
            regions.set(value.id, box);
        }
    }

    function focus(id) {
        const box = regions.get(id);
        if (!box || !container.contains(box)) return false;
        for (const [key, node] of regions) node.dataset.active = String(key === id);
        // 확대·화면 크기 변경 후의 실제 영역 위치로 스크롤한다.
        box.scrollIntoView({block: "center", inline: "nearest", behavior: "auto"});
        return true;
    }

    return {setItems, focus, clear};
}
