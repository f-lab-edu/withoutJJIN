const PDFJS_ROOT = new URL("../vendor/pdfjs/6.5.438/", import.meta.url);

async function loadPdfJs() {
    const pdfjs = await import(new URL("build/pdf.min.mjs", PDFJS_ROOT).href);
    pdfjs.GlobalWorkerOptions.workerSrc = new URL("build/pdf.worker.min.mjs", PDFJS_ROOT).href;
    pdfjs.GlobalWorkerOptions.rendererSrc = new URL("build/pdf.renderer.min.mjs", PDFJS_ROOT).href;
    return pdfjs;
}

// PDF.js는 화면에서 PDF를 열 때만 불러온다. 라이브러리 로딩 실패가 워크스페이스 전체를 멈추지 않게 한다.
export function createWorkspacePdfViewer({container, loadLibrary = loadPdfJs,
    pixelRatio = () => globalThis.devicePixelRatio || 1}) {
    let active = null;

    function clear() {
        const previous = active;
        active = null;
        if (previous) {
            previous.detachAbort?.();
            previous.renderTask?.cancel();
            // 다운로드와 worker도 종료한다. 이전 문서의 늦은 응답은 새 화면에 그리지 않는다.
            if (previous.loadingTask) void previous.loadingTask.destroy().catch(() => {});
        }
        container.replaceChildren();
        container.hidden = true;
    }

    async function open(url, {signal} = {}) {
        clear();
        if (signal?.aborted) return null;
        const session = {};
        active = session;
        const isCurrent = () => active === session && !signal?.aborted;
        const onAbort = () => { if (active === session) clear(); };
        signal?.addEventListener("abort", onAbort, {once: true});
        session.detachAbort = () => signal?.removeEventListener("abort", onAbort);

        try {
            const pdfjs = await loadLibrary();
            if (!isCurrent()) return null;
            session.loadingTask = pdfjs.getDocument({
                url, withCredentials: false, isEvalSupported: false,
                cMapUrl: new URL("cmaps/", PDFJS_ROOT).href, cMapPacked: true,
                standardFontDataUrl: new URL("standard_fonts/", PDFJS_ROOT).href,
                wasmUrl: new URL("wasm/", PDFJS_ROOT).href,
                iccUrl: new URL("iccs/", PDFJS_ROOT).href
            });
            const pdf = await session.loadingTask.promise;
            if (!isCurrent()) return null;
            container.hidden = false;
            const style = container.ownerDocument.defaultView?.getComputedStyle(container);
            const horizontalPadding = (Number.parseFloat(style?.paddingLeft) || 0)
                + (Number.parseFloat(style?.paddingRight) || 0);
            // clientWidth에는 padding이 포함된다. 페이지는 실제 콘텐츠 너비 안에 그린다.
            const displayWidth = Math.min(Math.max(1, (container.clientWidth || 960) - horizontalPadding), 1200);
            const ratio = Math.max(1, Math.min(pixelRatio(), 2));
            for (let pageNumber = 1; pageNumber <= pdf.numPages; pageNumber++) {
                const page = await pdf.getPage(pageNumber);
                if (!isCurrent()) return null;
                const original = page.getViewport({scale: 1});
                const viewport = page.getViewport({scale: displayWidth / original.width});
                const canvas = container.ownerDocument.createElement("canvas");
                canvas.width = Math.ceil(viewport.width * ratio);
                canvas.height = Math.ceil(viewport.height * ratio);
                canvas.style.width = viewport.width + "px";
                canvas.style.height = "auto";
                canvas.dataset.pageNumber = String(pageNumber);
                canvas.setAttribute("role", "img");
                canvas.setAttribute("aria-label", `PDF ${pageNumber}페이지`);
                const pageElement = container.ownerDocument.createElement("div");
                pageElement.className = "pdf-page";
                pageElement.style.width = viewport.width + "px";
                pageElement.dataset.pageNumber = String(pageNumber);
                pageElement.dataset.ready = "false";
                pageElement.append(canvas);
                container.append(pageElement);
                session.renderTask = page.render({
                    canvasContext: canvas.getContext("2d"), viewport,
                    transform: ratio === 1 ? null : [ratio, 0, 0, ratio, 0, 0]
                });
                await session.renderTask.promise;
                if (!isCurrent()) return null;
                session.renderTask = null;
                pageElement.dataset.ready = "true";
                page.cleanup();
            }
            return pdf.numPages;
        } catch (error) {
            if (!isCurrent()) return null;
            clear();
            // S3의 서명 URL이나 PDF 내용을 오류 문구에 노출하지 않는다.
            const message = error.name === "PasswordException"
                ? "암호로 보호된 PDF는 미리볼 수 없어요. 새 창에서 열어 주세요."
                : "PDF를 표시하지 못했어요. 다시 열거나 새 창에서 확인해 주세요.";
            throw new Error(message);
        }
    }

    return {open, clear};
}
