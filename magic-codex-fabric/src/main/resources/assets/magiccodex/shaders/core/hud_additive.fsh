#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
in vec2 texCoord0;
in vec4 vertexColor;
out vec4 fragColor;

// Additive light for UI effects: premultiplied texel times tint, alpha 0 so the
// (ONE, ONE_MINUS_SRC_ALPHA) blend of the HUD layer leaves the destination untouched and adds this colour.
void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    vec4 tint = vertexColor * ColorModulator;
    fragColor = vec4(texel.rgb * tint.rgb * tint.a, 0.0);
}
