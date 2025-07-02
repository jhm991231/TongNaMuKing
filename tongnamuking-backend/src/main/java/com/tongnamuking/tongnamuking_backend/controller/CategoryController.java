package com.tongnamuking.tongnamuking_backend.controller;

import com.tongnamuking.tongnamuking_backend.dto.CategoryChangeRequest;
import com.tongnamuking.tongnamuking_backend.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/category")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000"}, allowCredentials = "true")
public class CategoryController {
    
    private final CategoryService categoryService;
    
    @PostMapping("/change")
    public ResponseEntity<?> handleCategoryChange(@RequestBody CategoryChangeRequest request) {
        categoryService.saveCategoryChangeEvent(request);
        return ResponseEntity.ok().build();
    }
}