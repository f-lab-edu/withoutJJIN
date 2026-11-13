const SELECTION_COLORS = {
    green: {border: "#16785a", fill: "#39a87926"},
    yellow: {border: "#b7791f", fill: "#facc152e"},
    blue: {border: "#2563eb", fill: "#60a5fa2e"},
    pink: {border: "#be185d", fill: "#f472b62e"}
};

// 화면 좌표를 PDF 페이지의 왼쪽 위를 기준으로 한 0~1 비율로 변환한다.
export function normalizePagePoint(clientX, clientY, bounds) {
    if (![clientX, clientY, bounds.left, bounds.top, bounds.width, bounds.height].every(Number.isFinite)
            || bounds.width <= 0 || bounds.height <= 0) return null;
    const clamp = value => Math.max(0, Math.min(1, value));
    return {x: clamp((clientX - bounds.left) / bounds.width),
        y: clamp((clientY - bounds.top) / bounds.height)};
}

export function selectionRectangle(pageNumber, start, end) {
    return {pageNumber, x: Math.min(start.x, end.x), y: Math.min(start.y, end.y),
        width: Math.abs(end.x - start.x), height: Math.abs(end.y - start.y)};
}

// PDF 내용은 수정하지 않고 페이지 위에 HTML 사각형을 겹쳐 표시한다.
export function createPdfAreaSelector({container, onChange = () => {}}) {
    let enabled = false, color = "green";
    let selected = null, selectedBox = null, drag = null;

    function paintColor(box) {
        if (!box) return;
        box.style.borderColor = SELECTION_COLORS[color].border;
        box.style.backgroundColor = SELECTION_COLORS[color].fill;
    }

    function setColor(value) {
        if (!Object.hasOwn(SELECTION_COLORS, value)) throw new RangeError("지원하지 않는 선택 색상입니다.");
        color = value;
        paintColor(selectedBox);
        paintColor(drag?.box);
        if (selected) {
            selected = {...selected, color};
            onChange({...selected});
        }
    }

    function paint(box, area) {
        box.style.left = area.x * 100 + "%";
        box.style.top = area.y * 100 + "%";
        box.style.width = area.width * 100 + "%";
        box.style.height = area.height * 100 + "%";
    }

    function release(pointerId) {
        if (container.hasPointerCapture(pointerId)) container.releasePointerCapture(pointerId);
    }

    function cancelDrag() {
        if (!drag) return;
        const previous = drag;
        drag = null;
        previous.box.remove();
        if (selectedBox) selectedBox.hidden = false;
        release(previous.pointerId);
    }

    function clearSelection() {
        cancelDrag();
        selectedBox?.remove();
        selected = null;
        selectedBox = null;
        onChange(null);
    }

    function setEnabled(value) {
        enabled = Boolean(value);
        container.dataset.selectionEnabled = String(enabled);
        if (!enabled) cancelDrag();
    }

    function pointerDown(event) {
        if (!enabled || drag || event.button !== 0 || event.isPrimary === false) return;
        const page = event.target.closest?.(".pdf-page");
        if (!page || !container.contains(page) || page.dataset.ready !== "true") return;
        const pageNumber = Number(page.dataset.pageNumber);
        if (!Number.isInteger(pageNumber) || pageNumber < 1) return;
        const start = normalizePagePoint(event.clientX, event.clientY, page.getBoundingClientRect());
        if (!start) return;
        const box = container.ownerDocument.createElement("div");
        box.className = "pdf-selection";
        paintColor(box);
        box.setAttribute("aria-hidden", "true");
        paint(box, selectionRectangle(pageNumber, start, start));
        page.append(box);
        if (selectedBox) selectedBox.hidden = true;
        drag = {pointerId: event.pointerId, page, pageNumber, start, box};
        container.setPointerCapture(event.pointerId);
        container.focus({preventScroll: true});
        event.preventDefault();
    }

    function pointerMove(event) {
        if (!drag || event.pointerId !== drag.pointerId) return;
        const point = normalizePagePoint(event.clientX, event.clientY, drag.page.getBoundingClientRect());
        if (point) paint(drag.box, selectionRectangle(drag.pageNumber, drag.start, point));
    }

    function pointerUp(event) {
        if (!drag || event.pointerId !== drag.pointerId) return;
        const bounds = drag.page.getBoundingClientRect();
        const point = normalizePagePoint(event.clientX, event.clientY, bounds);
        if (!point) { cancelDrag(); return; }
        const area = selectionRectangle(drag.pageNumber, drag.start, point);
        // 단순 클릭이나 너무 얇은 선을 영역으로 확정하지 않는다.
        if (area.width * bounds.width < 4 || area.height * bounds.height < 4) {
            cancelDrag();
            return;
        }
        selectedBox?.remove();
        selectedBox = drag.box;
        selected = {...area, color};
        paint(selectedBox, area);
        const pointerId = drag.pointerId;
        drag = null;
        release(pointerId);
        onChange({...selected});
    }

    function pointerCancel(event) {
        if (drag?.pointerId === event.pointerId) cancelDrag();
    }

    function keyDown(event) {
        if (event.key !== "Escape") return;
        if (drag) cancelDrag();
        else clearSelection();
        event.preventDefault();
    }

    const handlers = {pointerdown: pointerDown, pointermove: pointerMove, pointerup: pointerUp,
        pointercancel: pointerCancel, lostpointercapture: pointerCancel, keydown: keyDown};
    for (const [type, handler] of Object.entries(handlers)) container.addEventListener(type, handler);
    setEnabled(false);
    return {setEnabled, setColor, clearSelection, getSelection: () => selected ? {...selected} : null,
        destroy() {
            setEnabled(false);
            clearSelection();
            for (const [type, handler] of Object.entries(handlers)) container.removeEventListener(type, handler);
        }};
}
