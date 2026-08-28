import { Link } from "react-router-dom";

export default function NotFoundPage() {
  return (
    <div>
      <h1>404 - Page Not Found</h1>
      <p>This page doesn't exist.</p>
      <Link to="/dashboard">Go to Dashboard</Link>
    </div>
  );
}