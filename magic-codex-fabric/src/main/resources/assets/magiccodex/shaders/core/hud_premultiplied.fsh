#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
in vec2 texCoord0;
in vec4 vertexColor;
out vec4 fragColor;

void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    vec4 tint = vertexColor * ColorModulator;
    // RGB has already been alpha weighted before mip generation. Do not multiply by texel.a again.
    fragColor = vec4(texel.rgb * tint.rgb * tint.a, texel.a * tint.a);
}
