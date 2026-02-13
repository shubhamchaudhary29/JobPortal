import { BrowserRouter } from 'react-router-dom'
import './App.css'
import AppRoutes from './routes/appRoute.jsx'

export default function App() {
  return (
    <BrowserRouter>
      <AppRoutes />
    </BrowserRouter>
  )
}

