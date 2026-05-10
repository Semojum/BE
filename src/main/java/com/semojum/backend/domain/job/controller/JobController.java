package com.semojum.backend.domain.job.controller;

import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.service.JobService;
import com.semojum.backend.global.exception.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    @PostMapping(consumes = "multipart/form-data")
    public ApiResponse<JobResponseDto.Create> createJob(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestPart("file") MultipartFile file,
            @RequestParam("mode") String mode
    ) throws Exception {
        return ApiResponse.success(jobService.createJob(userDetails.getUsername(), file, mode));
    }
}