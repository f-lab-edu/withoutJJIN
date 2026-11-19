package withoutjjin.withoutjjin.domain.document.conversion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;

@Component
public class WordPdfConverter {

    private static final int MAX_DOCX_BYTES = 20 * 1024 * 1024;// 20 MIB
    // 변환 서버의 PDF 응답을 무제한으로 메모리에 읽지 않도록 하는 초기 상한이다.
    private static final int MAX_PDF_BYTES = 64 * 1024 * 1024; // 64MiB
    private static final MediaType DOCX_MEDIA_TYPE =
            MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final RestClient restClient;

    public WordPdfConverter(@Qualifier("wordPdfRestClient") RestClient restClient) {
        this.restClient = restClient;
    }




    public ConvertedPdf convert(byte[] docxContent) {
        if (docxContent == null || docxContent.length == 0 || docxContent.length > MAX_DOCX_BYTES) {
            throw new IllegalArgumentException("DOCX 데이터는 비어 있지 않고 20MiB 이하여야 합니다.");
        }

        try {
            return restClient.post()// 	POST 요청 준비
                    .uri("/forms/libreoffice/convert")// 	Gotenberg의 변환 API 경로 지정
                    .contentType(MediaType.MULTIPART_FORM_DATA) //보내는 본문을 파일 전송에 사용하는 multipart 형식으로 지정
                    .accept(MediaType.APPLICATION_PDF) // 	PDF 응답을 원한다고 표시
                    .body(multipartBody(docxContent)) // 	DOCX를 담은 요청 본문 지정
                    .exchange((request, response) -> readConvertedPdf(response)); //	HTTP 요청을 실행하고 응답 처리 함수 실행
        } catch (ResourceAccessException exception) {
            throw new WordPdfConversionException(
                    "변환 서버에 연결하지 못했거나 응답 대기시간을 초과했습니다.", exception);
        } catch (RestClientException exception) {
            throw new WordPdfConversionException("변환 서버 요청에 실패했습니다.", exception);
        }
    }

    private MultiValueMap<String, Object> multipartBody(byte[] docxContent) { //DOCX를 HTTP 본문에 담기
        ByteArrayResource resource = new ByteArrayResource(docxContent) {
            @Override
            public String getFilename() {
                // 원래 파일명은 DocumentVersion에 따로 보관하므로 변환 요청에는 고정 이름을 사용한다.
                return "document.docx";
            }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(DOCX_MEDIA_TYPE);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        // files는 Gotenberg가 DOCX 파일을 받는 multipart 필드 이름이다.
        body.add("files", new HttpEntity<>(resource, headers));
        return body;
    }

    private ConvertedPdf readConvertedPdf(ClientHttpResponse response) throws IOException { //응답이 사용할 수 있는 PDF인지 검사
        if (!response.getStatusCode().is2xxSuccessful()) {
            // 원격 오류 본문에는 문서 내용이 포함될 수 있어 예외 메시지에 넣지 않는다.
            throw new WordPdfConversionException(
                    "DOCX PDF 변환에 실패했습니다. (HTTP " + response.getStatusCode().value() + ")");
        }
        MediaType contentType;
        try {
            contentType = response.getHeaders().getContentType();
        } catch (InvalidMediaTypeException exception) {
            throw new WordPdfConversionException("변환 서버의 Content-Type을 확인할 수 없습니다.", exception);
        }
        if (contentType == null || !MediaType.APPLICATION_PDF.includes(contentType)) {
            throw new WordPdfConversionException("변환 서버가 application/pdf 응답을 반환하지 않았습니다.");
        }
        if (response.getHeaders().getContentLength() > MAX_PDF_BYTES) {
            throw new WordPdfConversionException("변환된 PDF가 64MiB 제한을 초과했습니다.");
        }

        // Content-Length가 없거나 부정확한 응답도 실제 읽은 바이트 수로 제한한다.
        byte[] pdfContent = response.getBody().readNBytes(MAX_PDF_BYTES + 1); //④ 실제로 읽은 데이터 크기 확인
        if (pdfContent.length > MAX_PDF_BYTES) { //1바이트를 더 읽는 이유는 정확히 64MiB인 응답과 64MiB를 초과한 응답을 구분하기 위해서
            throw new WordPdfConversionException("변환된 PDF가 64MiB 제한을 초과했습니다.");
        }

        try (PDDocument pdf = Loader.loadPDF(pdfContent)) { //PDFBox로 PDF를 열고 페이지 수 확인
            //Loader.loadPDF(): PDF 바이트 배열을 PDFBox로 열어.
            int pageCount = pdf.getNumberOfPages();
            if (pageCount < 1) {
                throw new WordPdfConversionException("변환된 PDF에 페이지가 없습니다.");
            }
            return new ConvertedPdf(pdfContent, pageCount);
        } catch (IOException exception) {
            throw new WordPdfConversionException("변환 서버의 응답을 PDF로 열 수 없습니다.", exception);
        }



    }
}
