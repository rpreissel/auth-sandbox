package dev.authsandbox.devicelogin.controller;

import dev.authsandbox.devicelogin.dto.RegisterDeviceRequest;
import dev.authsandbox.devicelogin.dto.RegisterDeviceResponse;
import dev.authsandbox.devicelogin.service.DeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @PostMapping("/register")
    public ResponseEntity<RegisterDeviceResponse> register(
            @Valid @RequestBody RegisterDeviceRequest request) {
        RegisterDeviceResponse response = deviceService.registerDevice(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
