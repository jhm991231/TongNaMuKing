import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter as Router, Routes, Route, Link } from "react-router-dom";
import "./index.css";
import App from "./App.jsx";
import DogCakeApp from "./DogCakeApp.jsx";

function AppRouter() {
  return (
    <Router>
      <div>
        <nav
          style={{
            position: "fixed",
            top: 0,
            left: 0,
            right: 0,
            background: "#f8f9fa",
            padding: "10px 20px",
            borderBottom: "1px solid #dee2e6",
            zIndex: 9999,
            display: "flex",
            gap: "20px",
            justifyContent: "center",
          }}
        >
          <Link
            to="/dogcake"
            style={{
              textDecoration: "none",
              padding: "8px 16px",
              borderRadius: "4px",
              background: "#654321",
              color: "white",
              fontSize: "14px",
            }}
          >
            독케익 전용 순위
          </Link>
          <Link
            to="/multi"
            style={{
              textDecoration: "none",
              padding: "8px 16px",
              borderRadius: "4px",
              background: "#28a745",
              color: "white",
              fontSize: "14px",
            }}
          >
            다른 채널 검색하기
          </Link>
        </nav>

        <div style={{ paddingTop: "60px" }}>
          <Routes>
            <Route path="/" element={<DogCakeApp />} />
            <Route path="/dogcake" element={<DogCakeApp />} />
            <Route path="/multi" element={<App />} />
          </Routes>
        </div>
        
        <footer style={{
          position: "fixed",
          bottom: 0,
          left: 0,
          right: 0,
          textAlign: "center",
          padding: "10px 20px",
          background: "#f8f9fa",
          borderTop: "1px solid #dee2e6",
          color: "#6c757d",
          fontSize: "12px",
          zIndex: 1000
        }}>
          <p style={{ margin: 0 }}>버그제보 및 피드백 적극 환영 📧 jhm991231@gmail.com</p>
        </footer>
      </div>
    </Router>
  );
}

createRoot(document.getElementById("root")).render(
  <StrictMode>
    <AppRouter />
  </StrictMode>
);
