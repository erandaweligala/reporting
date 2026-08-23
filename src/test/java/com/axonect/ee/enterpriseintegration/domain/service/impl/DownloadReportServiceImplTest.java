package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.util.exception.type.BaseException;
import com.axonect.ee.enterpriseintegration.application.util.resultenum.ResponseCodeEnum;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReportRequest;
import com.axonect.ee.enterpriseintegration.domain.mapper.DownloadReportMapper;
import com.axonect.ee.enterpriseintegration.domain.repository.DownloadReportRepository;
import com.axonect.ee.enterpriseintegration.domain.service.UnifiedReportDownloadService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DownloadReportServiceImplTest {

    @Mock
    private DownloadReportRepository downloadReportRepository;

    @Mock
    private DownloadReportMapper downloadReportMapper;

    @Mock
    private UnifiedReportDownloadService unifiedReportDownloadService;

    @Mock
    private HttpServletRequest httpServletRequest;

    @InjectMocks
    private DownloadReportServiceImpl service;

    @TempDir
    Path tempDir;



    @Test
    void createDownloadReportRequest_WhenException_ShouldThrowBaseException() throws Exception {
        when(downloadReportMapper.mapDownloadRequestToEntity(any()))
                .thenThrow(new RuntimeException("DB down"));

        BaseException ex = assertThrows(
                BaseException.class,
                () -> service.createDownloadReportRequest(new DownloadReportRequest())
        );

        assertEquals(ResponseCodeEnum.LOG_NOT_CONNECTED.code(), ex.getResponseCode());
    }

    // ---------------- getReportFile ----------------

    @Test
    void getReportFile_WhenReportNotFound_ShouldThrow() {
        when(downloadReportRepository.findById(1L))
                .thenReturn(Optional.empty());

        BaseException ex = assertThrows(
                BaseException.class,
                () -> service.getReportFile(1L, httpServletRequest)
        );

        assertEquals(ResponseCodeEnum.REPORT_NOT_FOUND.code(), ex.getResponseCode());
    }


    @Test
    void getReportFile_WhenFileMissing_ShouldThrow() {
        service.reportOutputDirectory = tempDir.toString();

        DownloadReport report = new DownloadReport();
        report.setId(1L);

        when(downloadReportRepository.findById(1L))
                .thenReturn(Optional.of(report));

        BaseException ex = assertThrows(
                BaseException.class,
                () -> service.getReportFile(1L, httpServletRequest)
        );

        assertEquals(ResponseCodeEnum.REPORT_FILE_NOT_FOUND.code(), ex.getResponseCode());
    }

    // ---------------- getReportFilename ----------------

    @Test
    void getReportFilename_ShouldReturnFileName() throws Exception {
        service.reportOutputDirectory = tempDir.toString();

        File file = tempDir.resolve("5_report.csv").toFile();
        Files.write(file.toPath(), "x".getBytes());

        String name = service.getReportFilename(5L);
        assertEquals("5_report.csv", name);
    }

    @Test
    void getReportFilename_WhenMissing_ShouldThrow() {
        service.reportOutputDirectory = tempDir.toString();

        assertThrows(BaseException.class,
                () -> service.getReportFilename(99L));
    }

    // ---------------- findReportFile ----------------

    @Test
    void findReportFile_WhenMultipleFiles_ShouldReturnLatest() throws Exception {
        service.reportOutputDirectory = tempDir.toString();

        File oldFile = tempDir.resolve("3_old.csv").toFile();
        Files.write(oldFile.toPath(), "old".getBytes());
        oldFile.setLastModified(System.currentTimeMillis() - 10_000);

        File newFile = tempDir.resolve("3_new.csv").toFile();
        Files.write(newFile.toPath(), "new".getBytes());

        File result = service.findReportFile(3L);
        assertEquals(newFile.getName(), result.getName());
    }

    @Test
    void findReportFile_WhenDirMissing_ShouldThrow() {
        service.reportOutputDirectory = "invalid-dir";

        BaseException ex = assertThrows(
                BaseException.class,
                () -> service.findReportFile(1L)
        );

        assertEquals(ResponseCodeEnum.REPORT_DIR_ERROR.code(), ex.getResponseCode());
    }


    @Test
    void getReportFile_WhenFindReportFileThrowsBaseException_ShouldRethrow() {
        service.reportOutputDirectory = "non-existent-dir";

        DownloadReport report = new DownloadReport();
        report.setId(20L);

        when(downloadReportRepository.findById(20L))
                .thenReturn(Optional.of(report));

        BaseException ex = assertThrows(
                BaseException.class,
                () -> service.getReportFile(20L, httpServletRequest)
        );

        assertEquals(ResponseCodeEnum.REPORT_DIR_ERROR.code(), ex.getResponseCode());
    }

    @Test
    void getReportFile_WhenUnexpectedExceptionOccurs_ShouldThrowInternalServerError() {
        service.reportOutputDirectory = tempDir.toString();

        when(downloadReportRepository.findById(30L))
                .thenThrow(new RuntimeException("DB crash"));

        BaseException ex = assertThrows(
                BaseException.class,
                () -> service.getReportFile(30L, httpServletRequest)
        );

        assertEquals(ResponseCodeEnum.INTERNAL_SERVER_ERROR.code(), ex.getResponseCode());
    }


    @Test
    void getReportFile_WhenReportFileDoesNotExist_ShouldThrowReportFileNotFound() throws BaseException {
        service.reportOutputDirectory = tempDir.toString();

        DownloadReport report = new DownloadReport();
        report.setId(100L);

        when(downloadReportRepository.findById(100L))
                .thenReturn(Optional.of(report));

        // Create a path reference but DO NOT create the file
        File nonExistingFile = tempDir.resolve("100_report.csv").toFile();

        // Spy service to force findReportFile() to return a non-existing file
        DownloadReportServiceImpl spyService = spy(service);
        doReturn(nonExistingFile)
                .when(spyService).findReportFile(100L);

        BaseException ex = assertThrows(
                BaseException.class,
                () -> spyService.getReportFile(100L, httpServletRequest)
        );

        assertEquals(ResponseCodeEnum.REPORT_FILE_NOT_FOUND.code(), ex.getResponseCode());
    }

    @Test
    void getReportFilename_WhenReportFileDoesNotExist_ShouldThrowReportFileNotFound() throws BaseException {
        File nonExistingFile = new File("non-existing.csv");

        DownloadReportServiceImpl spyService = spy(service);
        doReturn(nonExistingFile)
                .when(spyService).findReportFile(200L);

        BaseException ex = assertThrows(
                BaseException.class,
                () -> spyService.getReportFilename(200L)
        );

        assertEquals(ResponseCodeEnum.REPORT_FILE_NOT_FOUND.code(), ex.getResponseCode());
    }

    @Test
    void findReportFile_WhenDirectoryIsNotADirectory_ShouldThrowReportDirError() throws BaseException, IOException {
        // Create a file instead of a directory
        File fileInsteadOfDir = tempDir.resolve("not_a_directory.txt").toFile();
        fileInsteadOfDir.createNewFile();

        service.reportOutputDirectory = fileInsteadOfDir.getAbsolutePath();

        BaseException ex = assertThrows(BaseException.class,
                () -> service.findReportFile(123L));

        assertEquals(ResponseCodeEnum.REPORT_DIR_ERROR.code(), ex.getResponseCode());
    }



}

