package kz.epharm.merch.producer.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class ProducerAuthenticationFilter(private val sessions:ProducerAuthService,
    @Value("\${app.merch.producer-auth-enabled:false}") private val enabled:Boolean):OncePerRequestFilter() {
    override fun shouldNotFilter(request:HttpServletRequest)=!enabled || !request.requestURI.removePrefix(request.contextPath).startsWith("/api/producer/")
    override fun doFilterInternal(request:HttpServletRequest,response:HttpServletResponse,chain:FilterChain) {
        SecurityContextHolder.clearContext()
        val header=request.getHeader("Authorization")
        if(header?.startsWith("Bearer ",ignoreCase=true)==true) {
            val principal=try {sessions.authenticate(header.substring(7).trim())} catch(_:DataAccessException) {
                response.status=503;response.contentType="application/json";response.characterEncoding="UTF-8"
                response.writer.write("""{"code":"UPSTREAM_UNAVAILABLE","message":"Сервис входа временно недоступен"}""");return
            }
            if(principal!=null) {
                request.setAttribute(ProducerAuthService.ACCESS_HASH,ProducerTokenCodec.hash(header.substring(7).trim()))
                SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(
                    principal,null,listOf(SimpleGrantedAuthority("ROLE_PRODUCER")))
            }
        }
        chain.doFilter(request,response)
    }
}
