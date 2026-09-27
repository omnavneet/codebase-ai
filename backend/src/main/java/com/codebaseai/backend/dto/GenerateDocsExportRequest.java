package com.codebaseai.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Body of the doc-generation export endpoint. The content is the documentation
 * the client already generated and is only ever written to a NEW file under the
 * project's {@code __generated__} directory — never to the source file.
 */
@Data
public class GenerateDocsExportRequest {
    @NotBlank
    @Size(max = 1000)
    private String filePath;

    @Size(max = 255)
    private String symbol;

    @NotBlank
    @Size(max = 256000)
    private String content;
}