package withoutjjin.withoutjjin.domain.document.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import withoutjjin.withoutjjin.domain.document.conversion.ConvertedPdf;
import withoutjjin.withoutjjin.domain.document.conversion.StoredPdf;
import withoutjjin.withoutjjin.domain.document.conversion.WordPdfConverter;
import withoutjjin.withoutjjin.domain.document.conversion.WordPdfConversionException;
import withoutjjin.withoutjjin.domain.document.storage.S3ObjectReader;
import withoutjjin.withoutjjin.domain.document.storage.S3PdfWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DocumentConversionServiceTest {

    private static final String ORIGINAL_KEY = "workspaces/10/uploads/original-docx";
    private static final String PDF_KEY = "workspaces/10/review-pdfs/converted.pdf";

    private final S3ObjectReader reader = mock(S3ObjectReader.class);
    private final WordPdfConverter converter = mock(WordPdfConverter.class);
    private final S3PdfWriter writer = mock(S3PdfWriter.class);
    private final DocumentConversionService service = new DocumentConversionService(reader, converter, writer);

    @Test
    @DisplayName("DOCX 다운로드·PDF 변환·PDF 업로드를 순서대로 실행하고 저장 결과를 반환한다")
    void convertsAndStoresPdfInOrder() {
        byte[] docx = {1, 2};
        byte[] pdf = {3, 4, 5};
        when(reader.downloadDocx(ORIGINAL_KEY)).thenReturn(docx);
        when(converter.convert(docx)).thenReturn(new ConvertedPdf(pdf, 2));
        when(writer.upload(10L, pdf)).thenReturn(PDF_KEY);

        StoredPdf result = service.convertAndStore(10L, ORIGINAL_KEY);

        InOrder order = inOrder(reader, converter, writer);
        order.verify(reader).downloadDocx(ORIGINAL_KEY);
        order.verify(converter).convert(docx);
        order.verify(writer).upload(10L, pdf);
        order.verifyNoMoreInteractions();
        assertThat(result).isEqualTo(new StoredPdf(PDF_KEY, 3L, 2));
    }

    @Test
    @DisplayName("DOCX 다운로드가 실패하면 PDF 변환과 업로드를 실행하지 않는다")
    void stopsBeforeConversionWhenDownloadFails() {
        SdkClientException failure = SdkClientException.create("S3 다운로드 실패");
        when(reader.downloadDocx(ORIGINAL_KEY)).thenThrow(failure);

        assertThatThrownBy(() -> service.convertAndStore(10L, ORIGINAL_KEY)).isSameAs(failure);

        verifyNoInteractions(converter, writer);
    }

    @Test
    @DisplayName("PDF 변환이 실패하면 PDF 업로드를 실행하지 않는다")
    void stopsBeforeUploadWhenConversionFails() {
        byte[] docx = {1, 2};
        WordPdfConversionException failure = new WordPdfConversionException("변환 실패");
        when(reader.downloadDocx(ORIGINAL_KEY)).thenReturn(docx);
        when(converter.convert(docx)).thenThrow(failure);

        assertThatThrownBy(() -> service.convertAndStore(10L, ORIGINAL_KEY)).isSameAs(failure);

        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("PDF 업로드가 실패하면 StoredPdf를 반환하지 않고 예외를 전달한다")
    void propagatesUploadFailure() {
        byte[] docx = {1, 2};
        byte[] pdf = {3, 4, 5};
        S3Exception failure = (S3Exception) S3Exception.builder()
                .statusCode(403).message("PDF 업로드 실패").build();
        when(reader.downloadDocx(ORIGINAL_KEY)).thenReturn(docx);
        when(converter.convert(docx)).thenReturn(new ConvertedPdf(pdf, 2));
        when(writer.upload(10L, pdf)).thenThrow(failure);

        assertThatThrownBy(() -> service.convertAndStore(10L, ORIGINAL_KEY)).isSameAs(failure);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    @DisplayName("워크스페이스 번호가 없거나 양수가 아니면 다운로드를 시작하지 않는다")
    void rejectsInvalidWorkspaceId(Long workspaceId) {
        assertThatThrownBy(() -> service.convertAndStore(workspaceId, ORIGINAL_KEY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("워크스페이스");

        verifyNoInteractions(reader, converter, writer);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("DOCX 저장 키가 없으면 다운로드를 시작하지 않는다")
    void rejectsMissingOriginalObjectKey(String objectKey) {
        assertThatThrownBy(() -> service.convertAndStore(10L, objectKey))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("저장 키");

        verifyNoInteractions(reader, converter, writer);
    }
}
