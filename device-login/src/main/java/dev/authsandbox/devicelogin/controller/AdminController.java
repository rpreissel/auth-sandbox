package dev.authsandbox.devicelogin.controller;

import dev.authsandbox.devicelogin.dto.AdminDeviceResponse;
import dev.authsandbox.devicelogin.dto.AdminRegistrationCodeResponse;
import dev.authsandbox.devicelogin.dto.CreateRegistrationCodeRequest;
import dev.authsandbox.devicelogin.service.AdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    // -----------------------------------------------------------------------
    // Registration codes
    // -----------------------------------------------------------------------

    @GetMapping("/registration-codes")
    public List<AdminRegistrationCodeResponse> listRegistrationCodes() {
        return adminService.listRegistrationCodes();
    }

    @PostMapping("/registration-codes")
    public ResponseEntity<AdminRegistrationCodeResponse> createRegistrationCode(
            @Valid @RequestBody CreateRegistrationCodeRequest request) {
        AdminRegistrationCodeResponse created = adminService.createRegistrationCode(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/registration-codes/{id}")
    public ResponseEntity<Void> deleteRegistrationCode(@PathVariable UUID id) {
        try {
            adminService.deleteRegistrationCode(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    // -----------------------------------------------------------------------
    // Devices
    // -----------------------------------------------------------------------

    @GetMapping("/devices")
    public List<AdminDeviceResponse> listDevices() {
        return adminService.listDevices();
    }

    @DeleteMapping("/devices/{id}")
    public ResponseEntity<Void> deleteDevice(@PathVariable UUID id) {
        try {
            adminService.deleteDevice(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException ex) {
            return ResponseEntity.notFound().build();
        }
    }
}
