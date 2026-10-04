<script setup>
import {Renderer, Program, Mesh, Color, Triangle} from 'ogl'
import {onMounted, onUnmounted, ref, watch} from 'vue'

// Vue Bits Galaxy shader, kept as a standalone background component.
const vertexShader = `
attribute vec2 uv;
attribute vec2 position;
varying vec2 vUv;
void main() { vUv = uv; gl_Position = vec4(position, 0.0, 1.0); }
`
const fragmentShader = `
precision highp float;
uniform float uTime,uStarSpeed,uDensity,uHueShift,uSpeed,uGlowIntensity,uSaturation,uTwinkleIntensity,uRotationSpeed,uRepulsionStrength,uMouseActiveFactor,uAutoCenterRepulsion;
uniform vec3 uResolution; uniform vec2 uFocal,uRotation,uMouse; uniform bool uMouseRepulsion,uTransparent; varying vec2 vUv;
#define NUM_LAYER 4.0
#define STAR_COLOR_CUTOFF 0.2
#define MAT45 mat2(0.7071,-0.7071,0.7071,0.7071)
#define PERIOD 3.0
float Hash21(vec2 p){p=fract(p*vec2(123.34,456.21));p+=dot(p,p+45.32);return fract(p.x*p.y);}
float tri(float x){return abs(fract(x)*2.0-1.0);}
float tris(float x){float t=fract(x);return 1.0-smoothstep(0.0,1.0,abs(2.0*t-1.0));}
float trisn(float x){return 2.0*tris(x)-1.0;}
vec3 hsv2rgb(vec3 c){vec4 K=vec4(1.0,2.0/3.0,1.0/3.0,3.0);vec3 p=abs(fract(c.xxx+K.xyz)*6.0-K.www);return c.z*mix(K.xxx,clamp(p-K.xxx,0.0,1.0),c.y);}
float Star(vec2 uv,float flare){float d=length(uv);float m=(0.05*uGlowIntensity)/max(d,0.001);float rays=smoothstep(0.0,1.0,1.0-abs(uv.x*uv.y*1000.0));m+=rays*flare*uGlowIntensity;uv*=MAT45;rays=smoothstep(0.0,1.0,1.0-abs(uv.x*uv.y*1000.0));m+=rays*0.3*flare*uGlowIntensity;return m*smoothstep(1.0,0.2,d);}
vec3 StarLayer(vec2 uv){vec3 col=vec3(0.0);vec2 gv=fract(uv)-0.5;vec2 id=floor(uv);for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){vec2 offset=vec2(float(x),float(y));vec2 si=id+offset;float seed=Hash21(si);float size=fract(seed*345.32);float glossLocal=tri(uStarSpeed/(PERIOD*seed+1.0));float flareSize=smoothstep(0.9,1.0,size)*glossLocal;float red=smoothstep(STAR_COLOR_CUTOFF,1.0,Hash21(si+1.0))+STAR_COLOR_CUTOFF;float blu=smoothstep(STAR_COLOR_CUTOFF,1.0,Hash21(si+3.0))+STAR_COLOR_CUTOFF;float grn=min(red,blu)*seed;vec3 base=vec3(red,grn,blu);float hue=atan(base.g-base.r,base.b-base.r)/(2.0*3.14159)+0.5;hue=fract(hue+uHueShift/360.0);float sat=length(base-vec3(dot(base,vec3(0.299,0.587,0.114))))*uSaturation;base=hsv2rgb(vec3(hue,sat,max(max(base.r,base.g),base.b)));vec2 pad=vec2(tris(seed*34.0+uTime*uSpeed/10.0),tris(seed*38.0+uTime*uSpeed/30.0))-0.5;float star=Star(gv-offset-pad,flareSize);float twinkle=mix(1.0,trisn(uTime*uSpeed+seed*6.2831)*0.5+1.0,uTwinkleIntensity);col+=star*size*base*twinkle;}return col;}
void main(){vec2 focalPx=uFocal*uResolution.xy;vec2 uv=(vUv*uResolution.xy-focalPx)/max(uResolution.y,1.0);if(uAutoCenterRepulsion>0.0){vec2 centerUV=vec2(0.0);float centerDist=length(uv-centerUV);uv+=normalize(uv-centerUV)*(uAutoCenterRepulsion/(centerDist+0.1))*0.05;}else if(uMouseRepulsion){vec2 mousePosUV=(uMouse*uResolution.xy-focalPx)/max(uResolution.y,1.0);float mouseDist=length(uv-mousePosUV);uv+=normalize(uv-mousePosUV)*(uRepulsionStrength/(mouseDist+0.1))*0.05*uMouseActiveFactor;}else uv+=(uMouse-vec2(0.5))*0.1*uMouseActiveFactor;float angle=uTime*uRotationSpeed;uv=mat2(cos(angle),-sin(angle),sin(angle),cos(angle))*uv;uv=mat2(uRotation.x,-uRotation.y,uRotation.y,uRotation.x)*uv;vec3 col=vec3(0.0);for(float i=0.0;i<1.0;i+=1.0/NUM_LAYER){float depth=fract(i+uStarSpeed*uSpeed);float scale=mix(20.0*uDensity,0.5*uDensity,depth);col+=StarLayer(uv*scale+i*453.32)*depth*smoothstep(1.0,0.9,depth);}if(uTransparent){float alpha=min(smoothstep(0.0,0.3,length(col)),1.0);gl_FragColor=vec4(col,alpha);}else gl_FragColor=vec4(col,1.0);}
`

const props = defineProps({
  focal:{type:Array,default:()=>[0.5,0.5]},rotation:{type:Array,default:()=>[1,0]},starSpeed:{type:Number,default:0.5},density:{type:Number,default:1},hueShift:{type:Number,default:140},disableAnimation:Boolean,speed:{type:Number,default:1},mouseInteraction:{type:Boolean,default:true},glowIntensity:{type:Number,default:0.3},saturation:{type:Number,default:0},mouseRepulsion:{type:Boolean,default:true},twinkleIntensity:{type:Number,default:0.3},rotationSpeed:{type:Number,default:0.1},repulsionStrength:{type:Number,default:2},autoCenterRepulsion:{type:Number,default:0},transparent:{type:Boolean,default:true},
})
const host=ref(null)
const target={x:0.5,y:0.5,active:0}, smooth={x:0.5,y:0.5,active:0}
let cleanup=()=>{}
const setup=()=>{
  if(!host.value)return
  const container=host.value,renderer=new Renderer({alpha:props.transparent,premultipliedAlpha:false}),gl=renderer.gl
  if(props.transparent){gl.enable(gl.BLEND);gl.blendFunc(gl.SRC_ALPHA,gl.ONE_MINUS_SRC_ALPHA);gl.clearColor(0,0,0,0)}else gl.clearColor(0,0,0,1)
  const program=new Program(gl,{vertex:vertexShader,fragment:fragmentShader,uniforms:{uTime:{value:0},uResolution:{value:new Color(1,1,1)},uFocal:{value:new Float32Array(props.focal)},uRotation:{value:new Float32Array(props.rotation)},uStarSpeed:{value:props.starSpeed},uDensity:{value:props.density},uHueShift:{value:props.hueShift},uSpeed:{value:props.speed},uMouse:{value:new Float32Array([.5,.5])},uGlowIntensity:{value:props.glowIntensity},uSaturation:{value:props.saturation},uMouseRepulsion:{value:props.mouseRepulsion},uTwinkleIntensity:{value:props.twinkleIntensity},uRotationSpeed:{value:props.rotationSpeed},uRepulsionStrength:{value:props.repulsionStrength},uMouseActiveFactor:{value:0},uAutoCenterRepulsion:{value:props.autoCenterRepulsion},uTransparent:{value:props.transparent}}})
  const mesh=new Mesh(gl,{geometry:new Triangle(gl),program})
  const resize=()=>{renderer.setSize(container.offsetWidth||window.innerWidth,container.offsetHeight||window.innerHeight);program.uniforms.uResolution.value=new Color(gl.canvas.width,gl.canvas.height,gl.canvas.width/Math.max(gl.canvas.height,1))}
  const move=event=>{const rect=container.getBoundingClientRect();target.x=(event.clientX-rect.left)/(rect.width||1);target.y=1-(event.clientY-rect.top)/(rect.height||1);target.active=1}
  const leave=()=>{target.active=0};let frame=0,destroyed=false
  const render=time=>{if(destroyed)return;frame=requestAnimationFrame(render);if(!props.disableAnimation){program.uniforms.uTime.value=time*.001;program.uniforms.uStarSpeed.value=(time*.001*props.starSpeed)/10.0;}smooth.x+=(target.x-smooth.x)*.05;smooth.y+=(target.y-smooth.y)*.05;smooth.active+=(target.active-smooth.active)*.05;program.uniforms.uMouse.value[0]=smooth.x;program.uniforms.uMouse.value[1]=smooth.y;program.uniforms.uMouseActiveFactor.value=smooth.active;renderer.render({scene:mesh})}
  container.appendChild(gl.canvas);window.addEventListener('resize',resize);if(props.mouseInteraction){container.addEventListener('mousemove',move);container.addEventListener('mouseleave',leave)}resize();frame=requestAnimationFrame(render)
  cleanup=()=>{destroyed=true;cancelAnimationFrame(frame);window.removeEventListener('resize',resize);if(props.mouseInteraction){container.removeEventListener('mousemove',move);container.removeEventListener('mouseleave',leave)}gl.getExtension('WEBGL_lose_context')?.loseContext();gl.canvas.remove()}
}
onMounted(()=>{cleanup();try{setup()}catch(error){host.value?.parentElement?.setAttribute('data-galaxy-error',error instanceof Error?error.name:'unknown')}})
onUnmounted(()=>cleanup())
watch(()=>[props.focal,props.rotation,props.starSpeed,props.density,props.hueShift,props.speed,props.glowIntensity,props.saturation,props.mouseInteraction,props.transparent],()=>{cleanup();setup()},{deep:true})
</script>
<template><div ref="host" class="galaxy-canvas" aria-hidden="true" /></template>
<style scoped>
.galaxy-canvas{position:absolute;inset:0;width:100%;height:100%;overflow:hidden;pointer-events:auto}.galaxy-canvas :deep(canvas){display:block;width:100%;height:100%}
</style>
