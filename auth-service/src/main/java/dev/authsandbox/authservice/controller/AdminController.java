package dev.authsandbox.authservice.controller;

import dev.authsandbox.authservice.dto.AdminDeviceResponse;
import dev.authsandbox.authservice.dto.AdminRegistrationCodeResponse;
import dev.authsandbox.authservice.dto.CreateRegistrationCodeRequest;
import dev.authsandbox.authservice.dto.CleanupResult;
import dev.authsandbox.authservice.dto.SyncResult;
import dev.authsandbox.authservice.service.RegistrationCodeService;
import dev.authsandbox.authservice.service.DeviceManagementService;
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

    private final RegistrationCodeService registrationCodeService;
    private final DeviceManagementService deviceManagementService;

    // -----------------------------------------------------------------------
    // Registration codes
    // -----------------------------------------------------------------------

    @GetMapping("/registration-codes")
    public List<AdminRegistrationCodeResponse> listRegistrationCodes() {
        return registrationCodeService.listRegistrationCodes();
    }

    @PostMapping("/registration-codes")
    public ResponseEntity<AdminRegistrationCodeResponse> createRegistrationCode(
            @Valid @RequestBody CreateRegistrationCodeRequest request) {
        AdminRegistrationCodeResponse created = registrationCodeService.createRegistrationCode(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/registration-codes/sync")
    public SyncResult syncRegistrationCodes() {
        return registrationCodeService.syncKeycloakUsers();
    }

    @PostMapping("/registration-codes/cleanup")
    public CleanupResult cleanupExpiredCodes() {
        return registrationCodeService.deleteExpiredCodes();
    }

    @DeleteMapping("/registration-codes/{id}")
    public ResponseEntity<Void> deleteRegistrationCode(@PathVariable UUID id) {
        try {
            registrationCodeService.deleteRegistrationCode(id);
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
        return deviceManagementService.listDevices();
    }

    @DeleteMapping("/devices/{id}")
    public ResponseEntity<Void> deleteDevice(@PathVariable UUID id) {
        try {
            deviceManagementService.deleteDevice(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException ex) {
            return ResponseEntity.notFound().build();
        }
    }
}
