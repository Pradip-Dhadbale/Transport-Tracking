package com.transport.vehicleservice.service.impl;

import com.transport.vehicleservice.client.RouteServiceClient;
import com.transport.vehicleservice.dto.response.ApiResponseDto;
import com.transport.vehicleservice.dto.response.RouteReferenceDto;
import com.transport.vehicleservice.dto.response.ScheduleReferenceDto;
import com.transport.vehicleservice.exception.RouteReferenceException;
import com.transport.vehicleservice.exception.RouteServiceUnavailableException;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RouteReferenceValidator {
    private final RouteServiceClient routeServiceClient;

    public void validate(Long routeId, Long scheduleId, LocalDate serviceDate) {
        try {
            ApiResponseDto<RouteReferenceDto> routeResponse =
                    routeServiceClient.getRoute(routeId);

            RouteReferenceDto route = requireData(routeResponse, "route");
            if (!route.isActive() || !routeId.equals(route.getRouteId())) {
                throw new RouteReferenceException("Route is not active");
            }

            ApiResponseDto<ScheduleReferenceDto> scheduleResponse =
                    routeServiceClient.getSchedule(routeId, scheduleId);
            ScheduleReferenceDto schedule = requireData(scheduleResponse, "schedule");
            if (!schedule.isActive() || !scheduleId.equals(schedule.getScheduleId())) {
                throw new RouteReferenceException(
                        "Schedule is not active for the selected route");
            }
            if (!runsOn(schedule.getDaysOfWeek(), serviceDate.getDayOfWeek())) {
                throw new RouteReferenceException(
                        "Schedule does not operate on the selected service date");
            }
        } catch (FeignException.NotFound exception) {
            throw new RouteReferenceException(
                    "Route or schedule was not found", exception);
        } catch (FeignException exception) {
            throw new RouteServiceUnavailableException(
                    "Route service is currently unavailable", exception);
        }
    }

    private <T> T requireData(ApiResponseDto<T> response, String referenceName) {
        if (response == null || !response.isSuccess() || response.getData() == null) {
            throw new RouteReferenceException(
                    "Invalid " + referenceName + " response from route service");
        }
        return response.getData();
    }

    private boolean runsOn(String rawDays, DayOfWeek day) {
        if (rawDays == null || rawDays.isBlank()) {
            return false;
        }
        String normalized = rawDays.trim().toUpperCase(Locale.ROOT);
        if ("DAILY".equals(normalized)) {
            return true;
        }
        Set<String> days = Arrays.stream(normalized.split("[,\\s]+"))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
        return days.contains(day.name().substring(0, 3));
    }
}
