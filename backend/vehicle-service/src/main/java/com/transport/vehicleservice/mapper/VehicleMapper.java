package com.transport.vehicleservice.mapper;

import com.transport.vehicleservice.dto.response.AssignmentResponseDto;
import com.transport.vehicleservice.dto.response.LocationResponseDto;
import com.transport.vehicleservice.dto.response.VehicleResponseDto;
import com.transport.vehicleservice.entity.Vehicle;
import com.transport.vehicleservice.entity.VehicleAssignment;
import com.transport.vehicleservice.entity.VehicleLocation;

public final class VehicleMapper {
    private VehicleMapper() {}

    public static VehicleResponseDto toVehicleDto(Vehicle vehicle) {
        return VehicleResponseDto.builder()
                .vehicleId(vehicle.getVehicleId())
                .vehicleNumber(vehicle.getVehicleNumber())
                .capacity(vehicle.getCapacity())
                .status(vehicle.getStatus())
                .active(vehicle.isActive())
                .createdAt(vehicle.getCreatedAt())
                .updatedAt(vehicle.getUpdatedAt())
                .build();
    }

    public static AssignmentResponseDto toAssignmentDto(VehicleAssignment assignment) {
        return AssignmentResponseDto.builder()
                .assignmentId(assignment.getAssignmentId())
                .vehicleId(assignment.getVehicle().getVehicleId())
                .routeId(assignment.getRouteId())
                .scheduleId(assignment.getScheduleId())
                .serviceDate(assignment.getServiceDate())
                .status(assignment.getStatus())
                .assignedAt(assignment.getAssignedAt())
                .completedAt(assignment.getCompletedAt())
                .build();
    }

    public static LocationResponseDto toLocationDto(VehicleLocation location) {
        return LocationResponseDto.builder()
                .locationId(location.getLocationId())
                .vehicleId(location.getVehicle().getVehicleId())
                .latitude(location.getLatitude())
                .longitude(location.getLongitude())
                .speedKph(location.getSpeedKph())
                .recordedAt(location.getRecordedAt())
                .build();
    }
}
