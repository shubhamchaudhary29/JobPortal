import { BrowserRouter } from 'react-router-dom'
import './App.css'
import AppRoutes from './routes/appRoute.jsx'
import { AuthProvider } from './auth/AuthContext.jsx'

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider><AppRoutes /></AuthProvider>
    </BrowserRouter>
  )
}
